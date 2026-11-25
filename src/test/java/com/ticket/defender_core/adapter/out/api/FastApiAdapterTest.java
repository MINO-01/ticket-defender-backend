package com.ticket.defender_core.adapter.out.api;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ticket.defender_core.adapter.out.api.dto.MacroAnalysisRequest;
import com.ticket.defender_core.adapter.out.api.dto.MacroAnalysisResponse;
import com.ticket.defender_core.adapter.out.api.dto.MultiEventAnalysisDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class FastApiAdapterTest {

    private static final String REQUEST_ID = "2136558c-2326-4430-a46d-0793494e9b49";
    private static final String PAYMENT_HASH = "a".repeat(64);
    private static final String DEVICE_HASH = "b".repeat(64);
    private static final String TEST_TRACK1_SHARED_SECRET = "test-only-track1-shared-secret-value";
    private static final String TOO_SHORT_UTF8_SECRET = "가".repeat(10);
    private static final String CONTROL_CHARACTER_SECRET = TEST_TRACK1_SHARED_SECRET + "\r\n";

    @Test
    @DisplayName("다공연 분석은 신규 가명 신호를 내부 인증 경로로 보내고 고정 버전 응답을 검증한다")
    void analyzeMultiEvent_sendsFixedContractAndAcceptsMatchingResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FastApiAdapter adapter = new FastApiAdapter(builder.build());
        ReflectionTestUtils.setField(adapter, "fastApiUrl", "https://fastapi.test/");
        setTrack1SharedSecret(adapter, TEST_TRACK1_SHARED_SECRET);
        MultiEventAnalysisDto.Request request = multiEventRequest();
        String response = """
                {"analysisId":"%s","ledgerVersionSetDigest":"%s",
                 "scoreVersion":"score-v2-exp2-multi-event-recurrence",
                 "projectionScoreVersion":"score-v2-exp2",
                 "projectionVersion":"projection-v2.2-exp2-ipwindow-softhub",
                 "algorithm":"GDS_LOUVAIN",
                 "algorithmVersion":"gds-2.6.6",
                 "candidates":[{"candidateId":"multi-%s","priorityBand":"MEDIUM",
                   "scoreVersion":"score-v2-exp2-multi-event-recurrence",
                   "projectionVersion":"projection-v2.2-exp2-ipwindow-softhub",
                   "distinctAccountCount":2,"distinctEventCount":2,"distinctPerformanceCount":2,
                   "verifiedRelationshipCount":2,
                   "sourceScopes":[
                     {"eventId":"event-a","performanceId":"00000000-0000-4000-8000-000000000001","ledgerVersionId":"00000000-0000-4000-8000-000000000011","sourceBatchId":"batch-a"},
                     {"eventId":"event-b","performanceId":"00000000-0000-4000-8000-000000000002","ledgerVersionId":"00000000-0000-4000-8000-000000000012","sourceBatchId":"batch-b"}],
                   "availableSignals":["refund_account_hash"],"excludedSignals":[],
                   "unavailableSignals":["address_hash","device_id_hash","phone_hash"],
                   "normalSharingPossibilities":["NORMAL_SHARED_POSSIBLE"],
                   "scoreBreakdown":{"L":0.8,"G":0.68,"B":0.41666666666666663,"Q":0.5,"R":0.3333333333333333,"score":0.2267,"quantityStatus":"POLICY_NOT_CONFIRMED","quantityReason":"POLICY_NOT_CONFIRMED"},
                   "memberAccountIds":["%s","%s"],
                   "relationships":[{"signalType":"refund_account_hash","accountIds":["%s","%s"],"sourceScopes":[
                     {"eventId":"event-a","performanceId":"00000000-0000-4000-8000-000000000001","ledgerVersionId":"00000000-0000-4000-8000-000000000011","sourceBatchId":"batch-a"},
                     {"eventId":"event-b","performanceId":"00000000-0000-4000-8000-000000000002","ledgerVersionId":"00000000-0000-4000-8000-000000000012","sourceBatchId":"batch-b"}]}]}]}
                """.formatted(request.analysisId(), request.ledgerVersionSetDigest(), "1".repeat(64),
                "a".repeat(64), "b".repeat(64), "a".repeat(64), "b".repeat(64));

        server.expect(requestTo("https://fastapi.test/api/v2/multi-event/analyze"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(httpRequest -> {
                    assertThat(httpRequest.getHeaders().get("X-TD-Track1-Key"))
                            .containsExactly(TEST_TRACK1_SHARED_SECRET);
                })
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("\"refundAccountHash\":\"" + "e".repeat(64) + "\""),
                        org.hamcrest.Matchers.containsString("\"phoneHash\":\"" + "f".repeat(64) + "\""))))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        MultiEventAnalysisDto.Response result = adapter.analyzeMultiEvent(request);

        assertThat(result.analysisId()).isEqualTo(request.analysisId());
        assertThat(result.projectionScoreVersion()).isEqualTo("score-v2-exp2");
        assertThat(result.candidates()).hasSize(1);
        server.verify();
    }

    @Test
    @DisplayName("다공연 응답의 projection score 버전이 exp2와 다르면 거부한다")
    void analyzeMultiEvent_rejectsMismatchedProjectionScoreVersion() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FastApiAdapter adapter = new FastApiAdapter(builder.build());
        ReflectionTestUtils.setField(adapter, "fastApiUrl", "https://fastapi.test");
        setTrack1SharedSecret(adapter, TEST_TRACK1_SHARED_SECRET);
        MultiEventAnalysisDto.Request request = multiEventRequest();
        String response = """
                {"analysisId":"%s","ledgerVersionSetDigest":"%s",
                 "scoreVersion":"score-v2-exp2-multi-event-recurrence",
                 "projectionScoreVersion":"score-v2-exp1",
                 "projectionVersion":"projection-v2.2-exp2-ipwindow-softhub",
                 "algorithm":"GDS_LOUVAIN",
                 "algorithmVersion":"gds-2.6.6",
                 "candidates":[]}
                """.formatted(request.analysisId(), request.ledgerVersionSetDigest());
        server.expect(requestTo("https://fastapi.test/api/v2/multi-event/analyze"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        assertThrows(FastApiResponseContractException.class, () -> adapter.analyzeMultiEvent(request));
        server.verify();
    }

    private MultiEventAnalysisDto.Request multiEventRequest() {
        UUID performanceOne = UUID.fromString("00000000-0000-4000-8000-000000000001");
        UUID performanceTwo = UUID.fromString("00000000-0000-4000-8000-000000000002");
        UUID versionOne = UUID.fromString("00000000-0000-4000-8000-000000000011");
        UUID versionTwo = UUID.fromString("00000000-0000-4000-8000-000000000012");
        OffsetDateTime asOf = OffsetDateTime.parse("2026-11-25T00:00:00Z");
        var scopes = List.of(
                new MultiEventAnalysisDto.LedgerScope("event-a", performanceOne, versionOne, "batch-a"),
                new MultiEventAnalysisDto.LedgerScope("event-b", performanceTwo, versionTwo, "batch-b"));
        var tickets = List.of(
                new MultiEventAnalysisDto.Ticket("a".repeat(64), "1".repeat(64), "event-a", performanceOne, versionOne,
                        1L, "supplier-a", "TRACK1_CONNECTION", "key-v1", asOf.minusDays(1), 1,
                        null, null, null, null, null, null, null, null, "e".repeat(64), "f".repeat(64), null,
                        "UNKNOWN", "QUANTITY_NOT_CONFIRMED", "source-v1"),
                new MultiEventAnalysisDto.Ticket("b".repeat(64), "2".repeat(64), "event-a", performanceOne, versionOne,
                        2L, "supplier-a", "TRACK1_CONNECTION", "key-v1", asOf.minusDays(1), 1,
                        null, null, null, null, null, null, null, null, "e".repeat(64), "f".repeat(64), null,
                        "UNKNOWN", "QUANTITY_NOT_CONFIRMED", "source-v1"),
                new MultiEventAnalysisDto.Ticket("a".repeat(64), "3".repeat(64), "event-b", performanceTwo, versionTwo,
                        1L, "supplier-a", "TRACK1_CONNECTION", "key-v1", asOf.minusDays(1), 1,
                        null, null, null, null, null, null, null, null, "e".repeat(64), "f".repeat(64), null,
                        "UNKNOWN", "QUANTITY_NOT_CONFIRMED", "source-v1"),
                new MultiEventAnalysisDto.Ticket("b".repeat(64), "4".repeat(64), "event-b", performanceTwo, versionTwo,
                        2L, "supplier-a", "TRACK1_CONNECTION", "key-v1", asOf.minusDays(1), 1,
                        null, null, null, null, null, null, null, null, "e".repeat(64), "f".repeat(64), null,
                        "UNKNOWN", "QUANTITY_NOT_CONFIRMED", "source-v1"));
        return new MultiEventAnalysisDto.Request(
                UUID.fromString(REQUEST_ID),
                UUID.fromString("2136558c-2326-4430-a46d-0793494e9b50"), "d".repeat(64),
                asOf, asOf.minusDays(30), asOf.minusDays(1),
                "score-v2-exp2-multi-event-recurrence", "projection-v2.2-exp2-ipwindow-softhub",
                scopes, tickets);
    }

    @Test
    @DisplayName("Track 1 분석 요청은 전용 내부 인증 헤더를 전송한다")
    void requestMacroAnalysis_sendsTrack1AuthHeaderExactlyOnce() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FastApiAdapter adapter = new FastApiAdapter(builder.build());
        ReflectionTestUtils.setField(adapter, "fastApiUrl", "https://fastapi.test");
        setTrack1SharedSecret(adapter, TEST_TRACK1_SHARED_SECRET);

        server.expect(requestTo("https://fastapi.test/api/v1/clusters/analyze"))
                .andExpect(request -> {
                    assertThat(request.getHeaders().get("X-TD-Track1-Key"))
                            .containsExactly(TEST_TRACK1_SHARED_SECRET);
                    assertThat(request.getHeaders().get("X-TD-Internal-Key")).isNull();
                    assertThat(request.getHeaders().get(HttpHeaders.AUTHORIZATION)).isNull();
                })
                .andRespond(withSuccess(successResponseJson(), MediaType.APPLICATION_JSON));

        adapter.requestMacroAnalysis(request());

        server.verify();
    }

    @Test
    @DisplayName("원격 평문 HTTP 주소에는 Track 1 인증 헤더를 전송하지 않는다")
    void requestMacroAnalysis_blocksRemoteHttpBeforeSendingTrack1Key() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FastApiAdapter adapter = new FastApiAdapter(builder.build());
        ReflectionTestUtils.setField(adapter, "fastApiUrl", "http://fastapi.test");
        setTrack1SharedSecret(adapter, TEST_TRACK1_SHARED_SECRET);
        server.expect(ExpectedCount.never(), requestTo("http://fastapi.test/api/v1/clusters/analyze"))
                .andRespond(withSuccess(successResponseJson(), MediaType.APPLICATION_JSON));

        assertThrows(FastApiAuthenticationConfigurationException.class,
                () -> adapter.requestMacroAnalysis(request()));

        server.verify();
    }

    @Test
    @DisplayName("유사 호스트, 사설 HTTP, 사용자정보와 잘못된 FastAPI URL은 호출 전에 차단한다")
    void requestMacroAnalysis_blocksUntrustedOrMalformedFastApiUrlBeforeHttpCall() {
        for (String url : List.of(
                "http://10.0.0.1", "http://fastapi", "http://localhost.evil",
                "http://127.0.0.1.evil", "ftp://fastapi.test",
                "https://user:synthetic-only@fastapi.test", "not-a-url", "http:///missing-host")) {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            FastApiAdapter adapter = new FastApiAdapter(builder.build());
            ReflectionTestUtils.setField(adapter, "fastApiUrl", url);
            setTrack1SharedSecret(adapter, TEST_TRACK1_SHARED_SECRET);
            server.expect(ExpectedCount.never(), request -> { })
                    .andRespond(withSuccess(successResponseJson(), MediaType.APPLICATION_JSON));

            FastApiAuthenticationConfigurationException error = assertThrows(
                    FastApiAuthenticationConfigurationException.class,
                    () -> adapter.requestMacroAnalysis(request())
            );

            assertThat(error.getMessage()).doesNotContain(url, TEST_TRACK1_SHARED_SECRET);
            server.verify();
        }
    }

    /** 분석 요청과 정상 응답이 계약에 맞게 오갑니다. */
    @Test
    @DisplayName("계약된 경로와 티켓별 요청으로 FastAPI 분석을 호출하고 응답을 해석한다")
    void requestMacroAnalysis_sendsContractAndReadsResponse() {
        // given
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FastApiAdapter adapter = new FastApiAdapter(builder.build());
        ReflectionTestUtils.setField(adapter, "fastApiUrl", "https://fastapi.test/");
        setTrack1SharedSecret(adapter, TEST_TRACK1_SHARED_SECRET);
        MacroAnalysisRequest request = request();
        String responseJson = """
                {
                  "requestId": "%s",
                  "status": "COMPLETED",
                  "algorithm": "LOUVAIN",
                  "algorithmVersion": "1.0.0",
                  "analyzedAt": "2026-10-08T10:15:30Z",
                  "clusters": [{
                    "clusterId": "cluster-001",
                    "members": [
                      {"accountId":"uid-1","reservationNo":"res-1","eventId":"event-1"},
                      {"accountId":"uid-2","reservationNo":"res-2","eventId":"event-1"}
                    ],
                    "paymentHashes": ["%s"],
                    "addressHashes": [],
                    "deviceIdHashes": ["%s"],
                    "ipHashes": [],
                    "riskScore": 0.91
                  }]
                }
                """.formatted(REQUEST_ID, PAYMENT_HASH, DEVICE_HASH);
        server.expect(requestTo("https://fastapi.test/api/v1/clusters/analyze"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("\"requestId\":\"" + REQUEST_ID + "\""),
                        org.hamcrest.Matchers.containsString("\"reservationNo\":\"res-1\""),
                        org.hamcrest.Matchers.containsString("\"deviceIdHash\":\"" + DEVICE_HASH + "\""))))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        // when
        MacroAnalysisResponse response = adapter.requestMacroAnalysis(request);

        // then
        server.verify();
        assertThat(response.requestId()).isEqualTo(REQUEST_ID);
        assertThat(response.status()).isEqualTo(MacroAnalysisResponse.Status.COMPLETED);
        assertThat(response.algorithm()).isEqualTo("LOUVAIN");
        assertThat(response.clusters()).hasSize(1);
        assertThat(response.clusters().get(0).members()).hasSize(2);
        assertThat(response.clusters().get(0).riskScore()).isEqualTo(0.91);
    }

    /** 요청 ID가 다른 응답은 거부됩니다. */
    @Test
    @DisplayName("요청 ID가 다른 응답은 유효한 분석 결과로 처리하지 않는다")
    void requestMacroAnalysis_rejectsMismatchedRequestId() {
        // given
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FastApiAdapter adapter = new FastApiAdapter(builder.build());
        ReflectionTestUtils.setField(adapter, "fastApiUrl", "https://fastapi.test");
        setTrack1SharedSecret(adapter, TEST_TRACK1_SHARED_SECRET);
        server.expect(requestTo("https://fastapi.test/api/v1/clusters/analyze"))
                .andRespond(withSuccess("""
                        {
                          "requestId": "wrong-request-id",
                          "status": "COMPLETED",
                          "algorithm": "LOUVAIN",
                          "algorithmVersion": "1.0.0",
                          "analyzedAt": "2026-10-08T10:15:30Z",
                          "clusters": []
                        }
                        """, MediaType.APPLICATION_JSON));

        // when / then
        assertThrows(IllegalStateException.class, () -> adapter.requestMacroAnalysis(request()));
        server.verify();
    }

    /** 필수 토큰 배열이 null이면 응답을 거부합니다. */
    @Test
    @DisplayName("필수 연결 토큰 배열 일부가 null이면 다른 배열에 값이 있어도 계약 위반으로 거부한다")
    void requestMacroAnalysis_rejectsNullRequiredTokenArray() {
        // given
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FastApiAdapter adapter = new FastApiAdapter(builder.build());
        ReflectionTestUtils.setField(adapter, "fastApiUrl", "https://fastapi.test");
        setTrack1SharedSecret(adapter, TEST_TRACK1_SHARED_SECRET);
        String responseJson = """
                {
                  "requestId": "%s",
                  "status": "COMPLETED",
                  "algorithm": "LOUVAIN",
                  "algorithmVersion": "1.0.0",
                  "analyzedAt": "2026-10-08T10:15:30Z",
                  "clusters": [{
                    "clusterId": "cluster-001",
                    "members": [
                      {"accountId":"uid-1","reservationNo":"res-1","eventId":"event-1"},
                      {"accountId":"uid-2","reservationNo":"res-2","eventId":"event-1"}
                    ],
                    "paymentHashes": null,
                    "addressHashes": ["%s"],
                    "deviceIdHashes": [],
                    "ipHashes": [],
                    "riskScore": 0.91
                  }]
                }
                """.formatted(REQUEST_ID, PAYMENT_HASH);
        server.expect(requestTo("https://fastapi.test/api/v1/clusters/analyze"))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        // when / then
        assertThrows(IllegalStateException.class, () -> adapter.requestMacroAnalysis(request()));
        server.verify();
    }

    @Test
    @DisplayName("형식이 올바른 토큰도 해당 군집의 요청 구성원에게 없으면 거부한다")
    void requestMacroAnalysis_rejectsTokenOutsideClusterMembers() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FastApiAdapter adapter = new FastApiAdapter(builder.build());
        ReflectionTestUtils.setField(adapter, "fastApiUrl", "https://fastapi.test");
        setTrack1SharedSecret(adapter, TEST_TRACK1_SHARED_SECRET);
        MacroAnalysisRequest request = new MacroAnalysisRequest(REQUEST_ID, List.of(
                request().tickets().get(0),
                request().tickets().get(1),
                new MacroAnalysisRequest.Ticket("uid-3", "res-3", "event-1",
                        "c".repeat(64), null, null, null)
        ));
        String responseJson = """
                {"requestId":"%s","status":"COMPLETED","algorithm":"LOUVAIN",
                 "algorithmVersion":"gds-2.6.6","analyzedAt":"2026-10-08T10:15:30Z",
                 "clusters":[{"clusterId":"cluster-001",
                   "members":[{"accountId":"uid-1","reservationNo":"res-1","eventId":"event-1"},
                              {"accountId":"uid-2","reservationNo":"res-2","eventId":"event-1"}],
                   "paymentHashes":["%s"],"addressHashes":[],"deviceIdHashes":[],"ipHashes":[],
                   "riskScore":0.91}]}
                """.formatted(REQUEST_ID, "c".repeat(64));
        server.expect(requestTo("https://fastapi.test/api/v1/clusters/analyze"))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        assertThrows(FastApiResponseContractException.class, () -> adapter.requestMacroAnalysis(request));
        server.verify();
    }

    @Test
    @DisplayName("HTTP 401, 410, 422, 500, 503은 완료된 빈 군집으로 해석하지 않는다")
    void requestMacroAnalysis_rejectsHttpErrors() {
        for (HttpStatus status : List.of(HttpStatus.UNAUTHORIZED, HttpStatus.GONE,
                HttpStatus.UNPROCESSABLE_ENTITY, HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.SERVICE_UNAVAILABLE)) {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            FastApiAdapter adapter = new FastApiAdapter(builder.build());
            ReflectionTestUtils.setField(adapter, "fastApiUrl", "https://fastapi.test");
            setTrack1SharedSecret(adapter, TEST_TRACK1_SHARED_SECRET);
            server.expect(requestTo("https://fastapi.test/api/v1/clusters/analyze"))
                    .andRespond(withStatus(status).body("{\"detail\":\"unavailable\"}"));

            assertThrows(org.springframework.web.client.RestClientException.class,
                    () -> adapter.requestMacroAnalysis(request()));
            server.verify();
        }
    }

    @Test
    @DisplayName("Track 1 인증 설정이 없거나 유효하지 않으면 HTTP 호출 전에 차단한다")
    void requestMacroAnalysis_blocksInvalidTrack1SecretBeforeHttpCall() {
        for (String secret : List.of("", TOO_SHORT_UTF8_SECRET, CONTROL_CHARACTER_SECRET)) {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            FastApiAdapter adapter = new FastApiAdapter(builder.build());
            ReflectionTestUtils.setField(adapter, "fastApiUrl", "https://fastapi.test");
            setTrack1SharedSecret(adapter, secret);
            server.expect(ExpectedCount.never(), requestTo("https://fastapi.test/api/v1/clusters/analyze"))
                    .andRespond(withSuccess(successResponseJson(), MediaType.APPLICATION_JSON));

            FastApiAuthenticationConfigurationException error = assertThrows(
                    FastApiAuthenticationConfigurationException.class,
                    () -> adapter.requestMacroAnalysis(request())
            );

            assertThat(error.getMessage()).doesNotContain(
                    TEST_TRACK1_SHARED_SECRET, TOO_SHORT_UTF8_SECRET, CONTROL_CHARACTER_SECRET);
            MacroAnalysisResponse fallback = adapter.analyzeFallback(request(), error);
            assertThat(fallback.status()).isEqualTo(MacroAnalysisResponse.Status.UNAVAILABLE);
            server.verify();
        }
    }

    @Test
    @DisplayName("분석 fallback 로그와 오류 메시지는 인증값을 포함하지 않는다")
    void analyzeFallback_doesNotExposeAuthenticationValue() {
        FastApiAdapter adapter = new FastApiAdapter(RestClient.builder().build());
        Logger logger = (Logger) LoggerFactory.getLogger(FastApiAdapter.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        logger.addAppender(events);

        try {
            MacroAnalysisResponse fallback = adapter.analyzeFallback(
                    request(), new IllegalStateException(TEST_TRACK1_SHARED_SECRET));

            assertThat(fallback.status()).isEqualTo(MacroAnalysisResponse.Status.UNAVAILABLE);
            assertThat(events.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .noneMatch(message -> message.contains(TEST_TRACK1_SHARED_SECRET));
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    /** 서버 장애는 분석 불가 상태로 처리됩니다. */
    @Test
    @DisplayName("Circuit Breaker Fallback은 분석 불가 상태를 반환한다")
    void analyzeFallback_returnsUnavailable() {
        // given
        FastApiAdapter adapter = new FastApiAdapter(RestClient.builder().build());
        MacroAnalysisRequest request = request();

        // when
        MacroAnalysisResponse response = adapter.analyzeFallback(
                request, new IllegalStateException("테스트 연결 실패"));

        // then
        assertThat(response).isNotNull();
        assertThat(response.requestId()).isEqualTo(REQUEST_ID);
        assertThat(response.status()).isEqualTo(MacroAnalysisResponse.Status.UNAVAILABLE);
        assertThat(response.clusters()).isEmpty();
    }

    /** 테스트용 예매 두 건을 요청에 담습니다. */
    private MacroAnalysisRequest request() {
        return new MacroAnalysisRequest(REQUEST_ID, List.of(
                new MacroAnalysisRequest.Ticket(
                        "uid-1", "res-1", "event-1", PAYMENT_HASH, null, DEVICE_HASH, null),
                new MacroAnalysisRequest.Ticket(
                        "uid-2", "res-2", "event-1", PAYMENT_HASH, null, DEVICE_HASH, null)
        ));
    }

    private String successResponseJson() {
        return """
                {
                  "requestId": "%s",
                  "status": "COMPLETED",
                  "algorithm": "LOUVAIN",
                  "algorithmVersion": "1.0.0",
                  "analyzedAt": "2026-10-08T10:15:30Z",
                  "clusters": []
                }
                """.formatted(REQUEST_ID);
    }

    private void setTrack1SharedSecret(FastApiAdapter adapter, String secret) {
        ReflectionTestUtils.setField(adapter, "track1InternalSharedSecret", secret);
    }
}
