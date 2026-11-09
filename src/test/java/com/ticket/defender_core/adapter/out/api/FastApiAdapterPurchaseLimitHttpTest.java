package com.ticket.defender_core.adapter.out.api;

import com.sun.net.httpserver.HttpServer;
import com.ticket.defender_core.adapter.out.api.dto.Track1PurchaseLimitAnalysisRequest;
import com.ticket.defender_core.adapter.out.api.dto.Track1PurchaseLimitAnalysisResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FastApiAdapterPurchaseLimitHttpTest {
    private static final String REQUEST_ID = "2136558c-2326-4430-a46d-0793494e9b49";
    private static final String SECRET = "test-only-track1-shared-secret-value";
    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PERFORMANCE_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Test
    void sendsFullySourcedV2PayloadToIsolatedEndpoint() throws IOException {
        AtomicReference<List<String>> keys = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = server(response("EVALUATED", "policy-v1", "[]"), keys, body);
        try {
            Track1PurchaseLimitAnalysisResponse result = adapter(server.getAddress().getPort()).requestPurchaseLimitAnalysis(request());
            assertThat(result.purchaseLimitEvaluations()).hasSize(1);
            assertThat(keys.get()).containsExactly(SECRET);
            assertThat(body.get()).contains("\"purchasedAt\"")
                    .contains("\"effectiveTicketQuantity\":2")
                    .contains("\"quantitySourceVersion\":\"ledger-2026-11-09\"");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refusesEvaluationForAnUnconfirmedPolicyEvenWhenDataReturns200() throws IOException {
        HttpServer server = server(response("EVALUATED", "policy-v1", "[]"), new AtomicReference<>(), new AtomicReference<>());
        try {
            assertThrows(FastApiResponseContractException.class,
                    () -> adapter(server.getAddress().getPort()).requestPurchaseLimitAnalysis(unconfirmedRequest()));
        } finally {
            server.stop(0);
        }
    }

    private FastApiAdapter adapter(int port) {
        FastApiAdapter adapter = new FastApiAdapter(RestClient.builder());
        ReflectionTestUtils.setField(adapter, "fastApiUrl", "http://127.0.0.1:" + port);
        ReflectionTestUtils.setField(adapter, "track1InternalSharedSecret", SECRET);
        return adapter;
    }

    private Track1PurchaseLimitAnalysisRequest request() {
        return new Track1PurchaseLimitAnalysisRequest(REQUEST_ID, List.of(new Track1PurchaseLimitAnalysisRequest.PerformanceScope(
                EVENT_ID, "event-external", PERFORMANCE_ID,
                Track1PurchaseLimitAnalysisRequest.PolicyVerificationStatus.CONFIRMED,
                new Track1PurchaseLimitAnalysisRequest.ConfirmedPolicy(2, "policy-v1",
                        OffsetDateTime.parse("2026-11-01T00:00:00Z"), null),
                Track1PurchaseLimitAnalysisRequest.QuantityDataStatus.COMPLETE, "ledger-2026-11-09",
                OffsetDateTime.parse("2026-11-09T00:00:00Z"),
                Track1PurchaseLimitAnalysisRequest.CancellationHandling.NETTED,
                Track1PurchaseLimitAnalysisRequest.DuplicateHandling.DEDUPLICATED, null)),
                List.of(ticket()));
    }

    private Track1PurchaseLimitAnalysisRequest unconfirmedRequest() {
        return new Track1PurchaseLimitAnalysisRequest(REQUEST_ID, List.of(new Track1PurchaseLimitAnalysisRequest.PerformanceScope(
                EVENT_ID, "event-external", PERFORMANCE_ID,
                Track1PurchaseLimitAnalysisRequest.PolicyVerificationStatus.POLICY_NOT_CONFIRMED, null,
                Track1PurchaseLimitAnalysisRequest.QuantityDataStatus.UNKNOWN, null, null,
                Track1PurchaseLimitAnalysisRequest.CancellationHandling.NOT_CONFIRMED,
                Track1PurchaseLimitAnalysisRequest.DuplicateHandling.NOT_CONFIRMED, "ledger unavailable")), List.of(ticket()));
    }

    private Track1PurchaseLimitAnalysisRequest.Ticket ticket() {
        return new Track1PurchaseLimitAnalysisRequest.Ticket("account-1", "reservation-1", "event-external",
                PERFORMANCE_ID, OffsetDateTime.parse("2026-11-02T10:00:00Z"), 2,
                "a".repeat(64), null, "b".repeat(64), null);
    }

    private HttpServer server(String response, AtomicReference<List<String>> keys, AtomicReference<String> body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/api/v2/clusters/analyze", exchange -> {
            keys.set(exchange.getRequestHeaders().get("X-TD-Track1-Key"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
        });
        server.start();
        return server;
    }

    private String response(String status, String version, String findings) {
        return """
                {"requestId":"%s","status":"COMPLETED","algorithm":"LOUVAIN",
                "algorithmVersion":"gds-2.6.6;projection-v2-account;score-v2-exp1","analyzedAt":"2026-11-09T00:00:01Z",
                "clusters":[],"purchaseLimitEvaluations":[{"internalEventId":"%s","eventId":"event-external",
                "performanceId":"%s","status":"%s","policyVersion":"%s","accountLimitFindings":%s}]}
                """.formatted(REQUEST_ID, EVENT_ID, PERFORMANCE_ID, status, version, findings);
    }
}
