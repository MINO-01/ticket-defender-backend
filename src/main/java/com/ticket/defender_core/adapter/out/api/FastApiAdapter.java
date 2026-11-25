package com.ticket.defender_core.adapter.out.api;

import com.ticket.defender_core.adapter.out.api.dto.FastApiClusterResponse;
import com.ticket.defender_core.adapter.out.api.dto.MacroAnalysisRequest;
import com.ticket.defender_core.adapter.out.api.dto.MacroAnalysisResponse;
import com.ticket.defender_core.adapter.out.api.dto.Track1PurchaseLimitAnalysisRequest;
import com.ticket.defender_core.adapter.out.api.dto.Track1PurchaseLimitAnalysisResponse;
import com.ticket.defender_core.adapter.out.api.dto.Track1BulkJobDto;
import com.ticket.defender_core.adapter.out.api.dto.MultiEventAnalysisDto;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.UnknownContentTypeException;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Component
public class FastApiAdapter {

    private static final String ANALYSIS_PATH = "/api/v1/clusters/analyze";
    private static final String PURCHASE_LIMIT_ANALYSIS_PATH = "/api/v2/clusters/analyze";
    private static final String BULK_JOB_PATH = "/api/v2/clusters/jobs";
    private static final String MULTI_EVENT_ANALYSIS_PATH = "/api/v2/multi-event/analyze";
    private static final String TRACK1_AUTH_HEADER = "X-TD-Track1-Key";
    private static final int MIN_TRACK1_SHARED_SECRET_BYTES = 32;
    private static final Set<String> PERMITTED_HTTP_LOOPBACK_LITERALS = Set.of("127.0.0.1", "::1");
    private static final Pattern HASH_TOKEN_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

    private final RestClient restClient;

    @Value("${FASTAPI_URL:http://localhost:8000}")
    private String fastApiUrl;

    @Value("${ticket-defender.track1-internal-auth.shared-secret:}")
    private String track1InternalSharedSecret;

    /** 연결과 응답 대기 시간을 제한해 FastAPI 호출을 준비합니다. */
    @Autowired
    public FastApiAdapter(RestClient.Builder restClientBuilder,
            @Value("${ticket-defender.track1-internal-auth.read-timeout:PT60S}") Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new Track1RequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(readTimeout);
        this.restClient = restClientBuilder.requestFactory(factory).build();
    }

    /** Package-visible deterministic test constructor; production receives the configured timeout above. */
    FastApiAdapter(RestClient.Builder restClientBuilder) {
        this(restClientBuilder, Duration.ofSeconds(5));
    }

    /** 테스트용 RestClient를 받습니다. */
    FastApiAdapter(RestClient restClient) {
        this.restClient = restClient;
    }

    /** FastAPI에 그래프 분석을 요청하고 응답을 확인합니다. */
    @CircuitBreaker(name = "fastApi", fallbackMethod = "analyzeFallback")
    public MacroAnalysisResponse requestMacroAnalysis(MacroAnalysisRequest request) {
        String baseUrl = requirePermittedFastApiBaseUrl();
        String track1SharedSecret = requireTrack1SharedSecret();
        MacroAnalysisResponse response;
        try {
            response = restClient.post()
                    .uri(baseUrl + ANALYSIS_PATH)
                    .header(TRACK1_AUTH_HEADER, track1SharedSecret)
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::is3xxRedirection,
                            (clientRequest, clientResponse) -> {
                                throw new FastApiAuthenticationConfigurationException();
                            })
                    .body(MacroAnalysisResponse.class);
        } catch (RestClientException cause) {
            if (cause instanceof UnknownContentTypeException
                    || cause.contains(HttpMessageNotReadableException.class)) {
                throw new FastApiResponseContractException("FastAPI 응답 본문을 해석할 수 없습니다.", cause);
            }
            throw cause;
        }

        validateResponse(request, response);
        return response;
    }

    /** 인증 헤더를 보낼 FastAPI 기준 URL이 HTTPS 또는 검증된 loopback HTTP인지 확인합니다. */
    /** Calls the isolated Data #30 v2 contract only with a fully sourced ledger request. */
    public Track1PurchaseLimitAnalysisResponse requestPurchaseLimitAnalysis(
            Track1PurchaseLimitAnalysisRequest request
    ) {
        Track1PurchaseLimitContractValidator.request(request);
        String baseUrl = requirePermittedFastApiBaseUrl();
        String sharedSecret = requireTrack1SharedSecret();
        Track1PurchaseLimitAnalysisResponse response;
        try {
            response = restClient.post()
                    .uri(baseUrl + PURCHASE_LIMIT_ANALYSIS_PATH)
                    .header(TRACK1_AUTH_HEADER, sharedSecret)
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::is3xxRedirection,
                            (clientRequest, clientResponse) -> { throw new FastApiAuthenticationConfigurationException(); })
                    .body(Track1PurchaseLimitAnalysisResponse.class);
        } catch (RestClientException cause) {
            if (cause instanceof UnknownContentTypeException
                    || cause.contains(HttpMessageNotReadableException.class)) {
                throw new FastApiResponseContractException("FastAPI response body violates the contract", cause);
            }
            throw cause;
        }
        Track1PurchaseLimitContractValidator.response(request, response);
        validateResponse(asV1Request(request), asV1Response(response));
        return response;
    }

    /** Bulk API calls are intentionally short; durable progress belongs to Spring's job table. */
    public Track1BulkJobDto.Accepted createBulkJob(Track1BulkJobDto.Create request, String idempotencyKey) {
        return bulkPost(BULK_JOB_PATH, request, idempotencyKey, Track1BulkJobDto.Accepted.class);
    }
    public Track1BulkJobDto.Status bulkJobStatus(UUID jobId) {
        return bulkGet(BULK_JOB_PATH + "/" + jobId, Track1BulkJobDto.Status.class);
    }
    public Track1BulkJobDto.Results bulkResults(UUID jobId, String cursor) {
        String path = BULK_JOB_PATH + "/" + jobId + "/results?limit=100&memberLimit=100" + (cursor == null ? "" : "&cursor=" + java.net.URLEncoder.encode(cursor, StandardCharsets.UTF_8));
        return bulkGet(path, Track1BulkJobDto.Results.class);
    }
    public Track1BulkJobDto.Members bulkMembers(UUID jobId, String clusterId, String cursor) {
        String path = BULK_JOB_PATH + "/" + jobId + "/results/" + java.net.URLEncoder.encode(clusterId, StandardCharsets.UTF_8) + "/members?limit=500" + (cursor == null ? "" : "&cursor=" + java.net.URLEncoder.encode(cursor, StandardCharsets.UTF_8));
        return bulkGet(path, Track1BulkJobDto.Members.class);
    }
    public Track1BulkJobDto.SharedSignalObservationPage bulkSharedSignalObservations(UUID jobId, String cursor) {
        var page = bulkGet(BULK_JOB_PATH + "/" + jobId + "/auxiliary/shared-signal-observations?limit=500"
                + cursor(cursor), Track1BulkJobDto.SharedSignalObservationPage.class);
        Track1BulkAuxiliaryContractValidator.shared(jobId, page); return page;
    }
    public Track1BulkJobDto.PurchaseLimitEvaluationPage bulkPurchaseLimitEvaluations(UUID jobId, String cursor) {
        var page = bulkGet(BULK_JOB_PATH + "/" + jobId + "/auxiliary/purchase-limit-evaluations?limit=500"
                + cursor(cursor), Track1BulkJobDto.PurchaseLimitEvaluationPage.class);
        Track1BulkAuxiliaryContractValidator.evaluations(jobId, page); return page;
    }
    public Track1BulkJobDto.PurchaseLimitFindingPage bulkPurchaseLimitFindings(UUID jobId, UUID performanceId, String cursor) {
        var page = bulkGet(BULK_JOB_PATH + "/" + jobId + "/auxiliary/purchase-limit-evaluations/"
                + performanceId + "/findings?limit=500" + cursor(cursor), Track1BulkJobDto.PurchaseLimitFindingPage.class);
        Track1BulkAuxiliaryContractValidator.findings(jobId, performanceId, page); return page;
    }
    public Track1BulkJobDto.Accepted retryBulkJob(UUID jobId) { return bulkPost(BULK_JOB_PATH + "/" + jobId + "/retry", null, null, Track1BulkJobDto.Accepted.class); }
    /** 다공연 요청은 Spring이 고정한 원장 범위만 전송한다. 사용자 tenant 입력은 이 경로에 없다. */
    public MultiEventAnalysisDto.Response analyzeMultiEvent(MultiEventAnalysisDto.Request request) {
        if (request == null || request.analysisId() == null || request.tenantId() == null
                || request.ledgerScopes() == null || request.ledgerScopes().size() < 2
                || request.tickets() == null || request.tickets().isEmpty()) {
            throw new FastApiResponseContractException("Invalid multi-event request");
        }
        try {
            MultiEventAnalysisDto.Response response = restClient.post()
                    .uri(requirePermittedFastApiBaseUrl() + MULTI_EVENT_ANALYSIS_PATH)
                    .header(TRACK1_AUTH_HEADER, requireTrack1SharedSecret())
                    .body(request).retrieve()
                    .onStatus(HttpStatusCode::is3xxRedirection,
                            (a,b) -> { throw new FastApiAuthenticationConfigurationException(); })
                    .body(MultiEventAnalysisDto.Response.class);
            validateMultiEventResponse(request, response);
            return response;
        } catch (RestClientException cause) {
            if (cause instanceof UnknownContentTypeException || cause.contains(HttpMessageNotReadableException.class)) {
                throw new FastApiResponseContractException("FastAPI multi-event response violates the contract", cause);
            }
            throw cause;
        }
    }
    private static String cursor(String cursor) { return cursor == null ? "" : "&cursor=" + java.net.URLEncoder.encode(cursor, StandardCharsets.UTF_8); }
    private <T> T bulkGet(String path, Class<T> type) { return bulkRequest(restClient.get().uri(requirePermittedFastApiBaseUrl() + path), type); }
    private <T> T bulkPost(String path, Object body, String idempotencyKey, Class<T> type) {
        var request = restClient.post().uri(requirePermittedFastApiBaseUrl() + path).header(TRACK1_AUTH_HEADER, requireTrack1SharedSecret());
        if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey);
        if (body == null) return request.retrieve().onStatus(HttpStatusCode::is3xxRedirection, (a,b)-> { throw new FastApiAuthenticationConfigurationException(); }).body(type);
        return request.body(body).retrieve().onStatus(HttpStatusCode::is3xxRedirection, (a,b)-> { throw new FastApiAuthenticationConfigurationException(); }).body(type);
    }
    private <T> T bulkRequest(RestClient.RequestHeadersSpec<?> request, Class<T> type) {
        try { return request.header(TRACK1_AUTH_HEADER, requireTrack1SharedSecret()).retrieve()
                .onStatus(HttpStatusCode::is3xxRedirection, (a,b)-> { throw new FastApiAuthenticationConfigurationException(); }).body(type); }
        catch (RestClientException cause) { throw cause; }
    }

    private static void validateMultiEventResponse(MultiEventAnalysisDto.Request request, MultiEventAnalysisDto.Response response) {
        if (response == null || !request.analysisId().equals(response.analysisId())
                || !request.ledgerVersionSetDigest().equals(response.ledgerVersionSetDigest())
                || !request.scoreVersion().equals(response.scoreVersion())
                || !request.projectionVersion().equals(response.projectionVersion())
                || !"GDS_LOUVAIN".equals(response.algorithm()) || response.algorithmVersion() == null
                || !response.algorithmVersion().contains(request.projectionVersion())
                || !response.algorithmVersion().contains(request.scoreVersion()) || response.candidates() == null) {
            throw new FastApiResponseContractException("FastAPI multi-event response does not match the fixed request");
        }
        Set<String> requestedScopes = new HashSet<>();
        for (var scope : request.ledgerScopes()) requestedScopes.add(scopeKey(scope));
        Set<String> candidates = new HashSet<>();
        for (var candidate : response.candidates()) {
            if (candidate == null || candidate.candidateId() == null || !candidates.add(candidate.candidateId())
                    || candidate.memberAccountIds() == null || candidate.memberAccountIds().size() < 2
                    || candidate.relationships() == null || candidate.relationships().isEmpty()
                    || candidate.scoreBreakdown() == null || !Double.isFinite(candidate.scoreBreakdown().score())
                    || candidate.scoreBreakdown().score() < 0 || candidate.scoreBreakdown().score() > 1
                    || candidate.distinctAccountCount() != candidate.memberAccountIds().size()
                    || !scoreBreakdownValid(candidate)) {
                throw new FastApiResponseContractException("Invalid multi-event candidate response");
            }
            if (candidate.sourceScopes() == null || candidate.sourceScopes().size() < 2
                    || candidate.sourceScopes().stream().map(MultiEventAnalysisDto.LedgerScope::eventId).distinct().count() != candidate.distinctEventCount()
                    || candidate.sourceScopes().stream().map(MultiEventAnalysisDto.LedgerScope::performanceId).distinct().count() != candidate.distinctPerformanceCount())
                throw new FastApiResponseContractException("Multi-event candidate counts do not match its sources");
            Set<String> candidateScopeKeys = new HashSet<>();
            for (var scope : candidate.sourceScopes()) {
                String scopeKey = scopeKey(scope);
                if (!requestedScopes.contains(scopeKey)) throw new FastApiResponseContractException("Multi-event response has an unrequested source scope");
                candidateScopeKeys.add(scopeKey);
            }
            for (String account : candidate.memberAccountIds()) if (account == null || !HASH_TOKEN_PATTERN.matcher(account).matches()) {
                throw new FastApiResponseContractException("Multi-event response has an invalid account reference");
            }
            Set<String> members = new HashSet<>(candidate.memberAccountIds());
            Set<String> relationshipObservations = new HashSet<>();
            for (var relationship : candidate.relationships()) {
                if (relationship == null || relationship.signalType() == null
                        || !Set.of("refund_account_hash", "phone_hash", "device_id_hash", "address_hash").contains(relationship.signalType())
                        || relationship.accountIds() == null || relationship.accountIds().size() < 2
                        || relationship.sourceScopes() == null || relationship.sourceScopes().isEmpty()
                        || !members.equals(new HashSet<>(relationship.accountIds()))) {
                    throw new FastApiResponseContractException("Invalid multi-event relationship response");
                }
                for (String account : relationship.accountIds()) if (!HASH_TOKEN_PATTERN.matcher(account).matches()) {
                    throw new FastApiResponseContractException("Multi-event relationship has an invalid account reference");
                }
                Set<String> relationshipEvents = new HashSet<>();
                for (var scope : relationship.sourceScopes()) {
                    String scopeKey = scopeKey(scope);
                    if (!requestedScopes.contains(scopeKey)) throw new FastApiResponseContractException("Multi-event relationship has an unrequested source scope");
                    if (!candidateScopeKeys.contains(scopeKey)) throw new FastApiResponseContractException("Multi-event relationship source is outside its candidate");
                    relationshipEvents.add(scope.eventId());
                    relationshipObservations.add(relationship.signalType() + "|" + scopeKey);
                }
                if (relationshipEvents.size() < 2) throw new FastApiResponseContractException("Multi-event relationship lacks repeated event evidence");
            }
            if (relationshipObservations.size() != candidate.verifiedRelationshipCount()) throw new FastApiResponseContractException("Multi-event relationship count does not match its sources");
        }
    }

    private static String scopeKey(MultiEventAnalysisDto.LedgerScope scope) {
        if (scope == null || scope.eventId() == null || scope.performanceId() == null || scope.ledgerVersionId() == null || scope.sourceBatchId() == null)
            throw new FastApiResponseContractException("Multi-event source scope is incomplete");
        return scope.eventId() + "|" + scope.performanceId() + "|" + scope.ledgerVersionId() + "|" + scope.sourceBatchId();
    }

    private static boolean scoreBreakdownValid(MultiEventAnalysisDto.Candidate candidate) {
        var score = candidate.scoreBreakdown();
        double[] parts = {score.L(), score.G(), score.B(), score.Q(), score.R(), score.score()};
        for (double value : parts) if (!Double.isFinite(value) || value < 0 || value > 1) return false;
        double expectedRecurrence = Math.min((candidate.distinctEventCount() - 1) / 3.0, 1.0);
        double expectedBehavior = 0.5 * score.Q() + 0.5 * expectedRecurrence;
        double expectedScore = Math.round(score.L() * score.G() * score.B() * 10_000.0) / 10_000.0;
        return Math.abs(score.R() - expectedRecurrence) < 1.0e-9
                && Math.abs(score.B() - expectedBehavior) < 1.0e-9
                && Math.abs(score.score() - expectedScore) < 1.0e-9;
    }

    private MacroAnalysisRequest asV1Request(Track1PurchaseLimitAnalysisRequest request) {
        return new MacroAnalysisRequest(request.requestId(), request.tickets().stream()
                .map(ticket -> new MacroAnalysisRequest.Ticket(ticket.accountId(), ticket.reservationNo(), ticket.eventId(),
                        ticket.paymentHash(), ticket.addressHash(), ticket.deviceIdHash(), ticket.ipHash()))
                .toList());
    }

    private MacroAnalysisResponse asV1Response(Track1PurchaseLimitAnalysisResponse response) {
        return new MacroAnalysisResponse(response.requestId(), MacroAnalysisResponse.Status.COMPLETED,
                response.algorithm(), response.algorithmVersion(), response.analyzedAt(), response.clusters());
    }

    private String requirePermittedFastApiBaseUrl() {
        final URI endpoint;
        try {
            endpoint = new URI(fastApiUrl);
        } catch (URISyntaxException | NullPointerException invalidUrl) {
            throw new FastApiAuthenticationConfigurationException();
        }

        if (endpoint.isOpaque() || endpoint.getScheme() == null || endpoint.getHost() == null
                || endpoint.getUserInfo() != null || endpoint.getRawQuery() != null
                || endpoint.getRawFragment() != null || endpoint.getPort() > 65535 || endpoint.getPort() == 0) {
            throw new FastApiAuthenticationConfigurationException();
        }
        if ("https".equalsIgnoreCase(endpoint.getScheme())) {
            return stripTrailingSlash(endpoint.toString());
        }
        if (!"http".equalsIgnoreCase(endpoint.getScheme())) {
            throw new FastApiAuthenticationConfigurationException();
        }

        InetAddress loopbackAddress = resolvePermittedHttpLoopback(endpoint.getHost());
        if (loopbackAddress == null) {
            throw new FastApiAuthenticationConfigurationException();
        }
        return loopbackHttpUrl(endpoint, loopbackAddress);
    }

    /** localhost는 모든 해석 결과가 loopback일 때만 수치 주소로 고정해 재해석을 방지합니다. */
    private InetAddress resolvePermittedHttpLoopback(String host) {
        try {
            if ("localhost".equalsIgnoreCase(host)) {
                InetAddress[] resolved = InetAddress.getAllByName(host);
                if (resolved.length == 0 || java.util.Arrays.stream(resolved).anyMatch(address -> !address.isLoopbackAddress())) {
                    return null;
                }
                return java.util.Arrays.stream(resolved)
                        .filter(Inet4Address.class::isInstance)
                        .findFirst()
                        .orElse(resolved[0]);
            }

            String literalHost = host.startsWith("[") && host.endsWith("]")
                    ? host.substring(1, host.length() - 1) : host;
            if (!PERMITTED_HTTP_LOOPBACK_LITERALS.contains(literalHost)) {
                return null;
            }
            InetAddress address = InetAddress.getByName(literalHost);
            return address.isLoopbackAddress() ? address : null;
        } catch (UnknownHostException invalidHost) {
            return null;
        }
    }

    /** 검증한 loopback 수치 주소를 사용해 HTTP 연결의 대상이 바뀌지 않도록 기준 URL을 구성합니다. */
    private String loopbackHttpUrl(URI endpoint, InetAddress loopbackAddress) {
        try {
            URI pinnedEndpoint = new URI(endpoint.getScheme(), null, loopbackAddress.getHostAddress(), endpoint.getPort(),
                    endpoint.getPath(), null, null);
            return stripTrailingSlash(pinnedEndpoint.toString());
        } catch (URISyntaxException invalidEndpoint) {
            throw new FastApiAuthenticationConfigurationException();
        }
    }

    private String stripTrailingSlash(String baseUrl) {
        return baseUrl.replaceAll("/+$", "");
    }

    private String requireTrack1SharedSecret() {
        if (track1InternalSharedSecret == null || track1InternalSharedSecret.isBlank()
                || track1InternalSharedSecret.getBytes(StandardCharsets.UTF_8).length < MIN_TRACK1_SHARED_SECRET_BYTES
                || containsHttpHeaderControlCharacter(track1InternalSharedSecret)) {
            throw new FastApiAuthenticationConfigurationException();
        }
        return track1InternalSharedSecret;
    }

    private boolean containsHttpHeaderControlCharacter(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    /** 인증 헤더가 새 대상에 자동 재전송되지 않도록 이 요청의 HTTP redirect를 사용하지 않습니다. */
    private static final class Track1RequestFactory extends SimpleClientHttpRequestFactory {
        @Override
        protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
            super.prepareConnection(connection, httpMethod);
            connection.setInstanceFollowRedirects(false);
        }
    }

    /** 호출에 실패하면 적발 결과 대신 분석 불가 상태를 반환합니다. */
    public MacroAnalysisResponse analyzeFallback(MacroAnalysisRequest request, Throwable cause) {
        String causeName = cause == null ? "알 수 없는 오류" : cause.getClass().getSimpleName();
        log.error("FastAPI 그래프 분석을 완료하지 못했습니다. 분석 불가 상태로 처리합니다. 원인 유형: {}", causeName);
        return MacroAnalysisResponse.unavailable(request.requestId());
    }

    /** 응답 계약 위반은 장애 fallback으로 변환하지 않습니다. */
    public MacroAnalysisResponse analyzeFallback(MacroAnalysisRequest request, FastApiResponseContractException cause) {
        log.error("FastAPI가 계약에 맞지 않는 분석 결과를 반환했습니다. 요청 ID: {}", request.requestId());
        throw cause;
    }

    /** Track 1 인증 설정 오류는 분석 불가로 기록하되 설정값 자체는 남기지 않습니다. */
    public MacroAnalysisResponse analyzeFallback(
            MacroAnalysisRequest request, FastApiAuthenticationConfigurationException cause
    ) {
        log.error("Track 1 내부 인증 설정이 유효하지 않아 분석을 시작하지 않았습니다.");
        return MacroAnalysisResponse.unavailable(request.requestId());
    }

    /** 요청 ID와 분석 정보, 군집 목록이 응답에 있는지 확인합니다. */
    private void validateResponse(MacroAnalysisRequest request, MacroAnalysisResponse response) {
        if (response == null) {
            throw new FastApiResponseContractException("FastAPI 응답 본문이 비어 있습니다.");
        }
        if (!request.requestId().equals(response.requestId())) {
            throw new FastApiResponseContractException("FastAPI 응답의 요청 ID가 요청과 일치하지 않습니다.");
        }
        if (response.status() != MacroAnalysisResponse.Status.COMPLETED) {
            throw new FastApiResponseContractException("FastAPI가 완료되지 않은 분석 상태를 반환했습니다.");
        }
        if (!"LOUVAIN".equals(response.algorithm())) {
            throw new FastApiResponseContractException("FastAPI 분석 알고리즘이 계약된 Louvain과 일치하지 않습니다.");
        }
        if (response.algorithmVersion() == null || response.algorithmVersion().isBlank()) {
            throw new FastApiResponseContractException("FastAPI 응답에 알고리즘 버전이 없습니다.");
        }
        if (response.analyzedAt() == null) {
            throw new FastApiResponseContractException("FastAPI 응답에 분석 완료 시각이 없습니다.");
        }
        if (response.clusters() == null) {
            throw new FastApiResponseContractException("FastAPI 응답에 군집 목록이 없습니다.");
        }

        Map<TicketKey, MacroAnalysisRequest.Ticket> submittedTickets = new HashMap<>();
        request.tickets().forEach(ticket -> submittedTickets.put(TicketKey.from(ticket), ticket));

        Set<String> clusterIds = new HashSet<>();
        Set<TicketKey> allMembers = new HashSet<>();
        for (FastApiClusterResponse cluster : response.clusters()) {
            validateCluster(cluster, submittedTickets, clusterIds, allMembers);
        }
    }

    /** 군집 구성원과 연결 토큰이 요청 내용과 일치하는지 확인합니다. */
    private void validateCluster(
            FastApiClusterResponse cluster,
            Map<TicketKey, MacroAnalysisRequest.Ticket> submittedTickets,
            Set<String> clusterIds,
            Set<TicketKey> allMembers
    ) {
        if (cluster == null || cluster.clusterId() == null || cluster.clusterId().isBlank()) {
            throw new FastApiResponseContractException("FastAPI 응답에 군집 식별자가 없습니다.");
        }
        if (!clusterIds.add(cluster.clusterId())) {
            throw new FastApiResponseContractException("FastAPI 응답에 중복 군집 식별자가 있습니다.");
        }
        if (cluster.riskScore() == null || !Double.isFinite(cluster.riskScore())
                || cluster.riskScore() < 0 || cluster.riskScore() > 1) {
            throw new FastApiResponseContractException("군집 위험도는 0부터 1 사이의 조사 우선순위 점수여야 합니다.");
        }
        if (cluster.members() == null) {
            throw new FastApiResponseContractException("FastAPI 응답에 군집 구성원 목록이 없습니다.");
        }
        if (cluster.members().size() < 2) {
            throw new FastApiResponseContractException("의심 군집에는 서로 다른 예매 내역이 두 건 이상 필요합니다.");
        }

        Set<TicketKey> clusterMembers = new HashSet<>();
        Set<String> paymentHashes = new HashSet<>();
        Set<String> addressHashes = new HashSet<>();
        Set<String> deviceIdHashes = new HashSet<>();
        Set<String> ipHashes = new HashSet<>();
        for (FastApiClusterResponse.Member member : cluster.members()) {
            if (member == null || isBlank(member.accountId()) || isBlank(member.reservationNo())
                    || isBlank(member.eventId())) {
                throw new FastApiResponseContractException("군집 구성원의 계정, 예매, 공연 식별자가 모두 필요합니다.");
            }
            TicketKey memberKey = TicketKey.from(member);
            MacroAnalysisRequest.Ticket submittedTicket = submittedTickets.get(memberKey);
            if (submittedTicket == null) {
                throw new FastApiResponseContractException("FastAPI가 이번 요청에 포함되지 않은 예매 내역을 반환했습니다.");
            }
            if (!clusterMembers.add(memberKey) || !allMembers.add(memberKey)) {
                throw new FastApiResponseContractException("예매 내역이 응답에서 여러 번 군집 구성원으로 지정되었습니다.");
            }
            addToken(paymentHashes, submittedTicket.paymentHash());
            addToken(addressHashes, submittedTicket.addressHash());
            addToken(deviceIdHashes, submittedTicket.deviceIdHash());
            addToken(ipHashes, submittedTicket.ipHash());
        }

        if (cluster.paymentHashes() == null || cluster.addressHashes() == null
                || cluster.deviceIdHashes() == null || cluster.ipHashes() == null) {
            throw new FastApiResponseContractException("FastAPI 응답에 필수 연결 토큰 배열이 누락되었습니다.");
        }

        validateHashTokens(cluster.paymentHashes(), paymentHashes);
        validateHashTokens(cluster.addressHashes(), addressHashes);
        validateHashTokens(cluster.deviceIdHashes(), deviceIdHashes);
        validateHashTokens(cluster.ipHashes(), ipHashes);
        if (cluster.paymentHashes().isEmpty() && cluster.addressHashes().isEmpty()
                && cluster.deviceIdHashes().isEmpty() && cluster.ipHashes().isEmpty()) {
            throw new FastApiResponseContractException("군집을 뒷받침하는 가명 연결 신호가 없습니다.");
        }
    }

    private void addToken(Set<String> tokens, String token) {
        if (token != null) {
            tokens.add(token);
        }
    }

    /** 연결 토큰이 해당 군집 구성원의 요청 신호인지 확인합니다. */
    private void validateHashTokens(List<String> tokens, Set<String> submittedTokens) {
        if (tokens.stream().anyMatch(token -> token == null || !HASH_TOKEN_PATTERN.matcher(token).matches()
                || !submittedTokens.contains(token))) {
            throw new FastApiResponseContractException("분석 결과의 연결 토큰이 요청 군집과 일치하지 않습니다.");
        }
    }

    /** 값이 null이나 공백인지 확인합니다. */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record TicketKey(String accountId, String reservationNo, String eventId) {
        /** 요청 티켓의 식별 값을 묶습니다. */
        private static TicketKey from(MacroAnalysisRequest.Ticket ticket) {
            return new TicketKey(ticket.accountId(), ticket.reservationNo(), ticket.eventId());
        }

        /** 응답 구성원의 식별 값을 묶습니다. */
        private static TicketKey from(FastApiClusterResponse.Member member) {
            return new TicketKey(member.accountId(), member.reservationNo(), member.eventId());
        }
    }
}
