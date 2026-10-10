package com.ticket.defender_core.adapter.out.api;

import com.ticket.defender_core.adapter.out.api.dto.MacroAnalysisRequest;
import com.ticket.defender_core.adapter.out.api.dto.MacroAnalysisResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FastApiAdapterTest {

    private static final String REQUEST_ID = "2136558c-2326-4430-a46d-0793494e9b49";
    private static final String PAYMENT_HASH = "a".repeat(64);
    private static final String DEVICE_HASH = "b".repeat(64);

    /** 분석 요청과 정상 응답이 계약에 맞게 오갑니다. */
    @Test
    @DisplayName("계약된 경로와 티켓별 요청으로 FastAPI 분석을 호출하고 응답을 해석한다")
    void requestMacroAnalysis_sendsContractAndReadsResponse() {
        // given
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FastApiAdapter adapter = new FastApiAdapter(builder.build());
        ReflectionTestUtils.setField(adapter, "fastApiUrl", "http://fastapi.test/");
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
        server.expect(requestTo("http://fastapi.test/api/v1/clusters/analyze"))
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
        ReflectionTestUtils.setField(adapter, "fastApiUrl", "http://fastapi.test");
        server.expect(requestTo("http://fastapi.test/api/v1/clusters/analyze"))
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
        ReflectionTestUtils.setField(adapter, "fastApiUrl", "http://fastapi.test");
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
        server.expect(requestTo("http://fastapi.test/api/v1/clusters/analyze"))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        // when / then
        assertThrows(IllegalStateException.class, () -> adapter.requestMacroAnalysis(request()));
        server.verify();
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
}
