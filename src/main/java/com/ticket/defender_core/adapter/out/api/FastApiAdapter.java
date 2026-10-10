package com.ticket.defender_core.adapter.out.api;

import com.ticket.defender_core.adapter.out.api.dto.FastApiClusterResponse;
import com.ticket.defender_core.adapter.out.api.dto.MacroAnalysisRequest;
import com.ticket.defender_core.adapter.out.api.dto.MacroAnalysisResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Component
public class FastApiAdapter {

    private static final String ANALYSIS_PATH = "/api/v1/clusters/analyze";
    private static final Pattern HASH_TOKEN_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

    private final RestClient restClient;

    @Value("${FASTAPI_URL:http://localhost:8000}")
    private String fastApiUrl;

    /** 연결과 응답 대기 시간을 제한해 FastAPI 호출을 준비합니다. */
    @Autowired
    public FastApiAdapter(RestClient.Builder restClientBuilder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = restClientBuilder.requestFactory(factory).build();
    }

    /** 테스트용 RestClient를 받습니다. */
    FastApiAdapter(RestClient restClient) {
        this.restClient = restClient;
    }

    /** FastAPI에 그래프 분석을 요청하고 응답을 확인합니다. */
    @CircuitBreaker(name = "fastApi", fallbackMethod = "analyzeFallback")
    public MacroAnalysisResponse requestMacroAnalysis(MacroAnalysisRequest request) {
        String baseUrl = fastApiUrl.replaceAll("/+$", "");
        MacroAnalysisResponse response = restClient.post()
                .uri(baseUrl + ANALYSIS_PATH)
                .body(request)
                .retrieve()
                .body(MacroAnalysisResponse.class);

        validateResponse(request, response);
        return response;
    }

    /** 호출에 실패하면 적발 결과 대신 분석 불가 상태를 반환합니다. */
    public MacroAnalysisResponse analyzeFallback(MacroAnalysisRequest request, Throwable cause) {
        String causeName = cause == null ? "알 수 없는 오류" : cause.getClass().getSimpleName();
        log.error("FastAPI 그래프 분석을 완료하지 못했습니다. 분석 불가 상태로 처리합니다. 원인: {}", causeName, cause);
        return MacroAnalysisResponse.unavailable(request.requestId());
    }

    /** 응답 계약 위반은 장애 fallback으로 변환하지 않습니다. */
    public MacroAnalysisResponse analyzeFallback(MacroAnalysisRequest request, FastApiResponseContractException cause) {
        log.error("FastAPI가 계약에 맞지 않는 분석 결과를 반환했습니다. 요청 ID: {}", request.requestId(), cause);
        throw cause;
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

        Set<TicketKey> submittedTickets = new HashSet<>();
        request.tickets().forEach(ticket -> submittedTickets.add(TicketKey.from(ticket)));

        Set<String> clusterIds = new HashSet<>();
        Set<TicketKey> allMembers = new HashSet<>();
        for (FastApiClusterResponse cluster : response.clusters()) {
            validateCluster(cluster, submittedTickets, clusterIds, allMembers);
        }
    }

    /** 군집 구성원과 연결 토큰이 요청 내용과 일치하는지 확인합니다. */
    private void validateCluster(
            FastApiClusterResponse cluster,
            Set<TicketKey> submittedTickets,
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
        for (FastApiClusterResponse.Member member : cluster.members()) {
            if (member == null || isBlank(member.accountId()) || isBlank(member.reservationNo())
                    || isBlank(member.eventId())) {
                throw new FastApiResponseContractException("군집 구성원의 계정·예매·공연 식별자가 모두 필요합니다.");
            }
            TicketKey memberKey = TicketKey.from(member);
            if (!submittedTickets.contains(memberKey)) {
                throw new FastApiResponseContractException("FastAPI가 이번 요청에 포함되지 않은 예매 내역을 반환했습니다.");
            }
            if (!clusterMembers.add(memberKey) || !allMembers.add(memberKey)) {
                throw new FastApiResponseContractException("예매 내역이 응답에서 여러 번 군집 구성원으로 지정되었습니다.");
            }
        }

        if (cluster.paymentHashes() == null || cluster.addressHashes() == null
                || cluster.deviceIdHashes() == null || cluster.ipHashes() == null) {
            throw new FastApiResponseContractException("FastAPI 응답에 필수 연결 토큰 배열이 누락되었습니다.");
        }

        validateHashTokens(cluster.paymentHashes());
        validateHashTokens(cluster.addressHashes());
        validateHashTokens(cluster.deviceIdHashes());
        validateHashTokens(cluster.ipHashes());
        if (cluster.paymentHashes().isEmpty() && cluster.addressHashes().isEmpty()
                && cluster.deviceIdHashes().isEmpty() && cluster.ipHashes().isEmpty()) {
            throw new FastApiResponseContractException("군집을 뒷받침하는 가명 연결 신호가 없습니다.");
        }
    }

    /** 연결 토큰이 64자리 소문자 16진수인지 확인합니다. */
    private void validateHashTokens(List<String> tokens) {
        if (tokens.stream().anyMatch(token -> token == null || !HASH_TOKEN_PATTERN.matcher(token).matches())) {
            throw new FastApiResponseContractException("분석 결과의 연결 토큰 형식이 계약과 일치하지 않습니다.");
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
