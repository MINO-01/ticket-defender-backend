package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.out.api.ListingContextClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class ListingContextClientTest {
    private static final String SHA = "a".repeat(64);
    private static final byte[] IMAGE = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};

    private FakeListingContextServer python;

    @BeforeEach
    void start() throws Exception {
        python = new FakeListingContextServer();
    }

    @AfterEach
    void stop() {
        python.close();
    }

    @Test
    void missingOrUnsafeConfigurationFailsClosedWithoutNetworkCall() {
        assertThat(client(false, python.baseUrl(), FakeListingContextServer.INTERNAL_KEY).configured()).isFalse();
        assertThat(client(true, python.baseUrl(), "").configured()).isFalse();
        assertThat(client(true, python.baseUrl(), "short-key").configured()).isFalse();
        assertThat(client(true, "", FakeListingContextServer.INTERNAL_KEY).configured()).isFalse();
        assertThat(client(true, "http://analysis.example.test", FakeListingContextServer.INTERNAL_KEY).configured())
                .isFalse();
        assertThat(client(true, "https://user:pw@analysis.example.test", FakeListingContextServer.INTERNAL_KEY)
                .configured()).isFalse();
        assertThat(client(true, "https://analysis.example.test", FakeListingContextServer.INTERNAL_KEY).configured())
                .isTrue();
        ListingContextClient.Result blocked = client(true, python.baseUrl(), "short-key")
                .analyze(UUID.randomUUID(), UUID.randomUUID(), SHA, "image/png", IMAGE);
        assertThat(blocked).isEqualTo(new ListingContextClient.Failure("ANALYZER_DISABLED", null, null));
        assertThat(python.received).isEmpty();
    }

    @Test
    void connectionFailureIsDistinctFromTimeout() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        assertThat(client(true, "http://127.0.0.1:" + closedPort, FakeListingContextServer.INTERNAL_KEY)
                .analyze(UUID.randomUUID(), UUID.randomUUID(), SHA, "image/png", IMAGE))
                .isEqualTo(new ListingContextClient.Failure("ANALYZER_CONNECTION_FAILED", null, null));
        python.responder = body -> new FakeListingContextServer.Reply(200, "{}", 1500);
        assertThat(analyze()).isEqualTo(new ListingContextClient.Failure("ANALYZER_TIMEOUT", null, null));
    }

    @Test
    void stalledResponseBodyAfterHeadersTimesOutWithinDeadline() {
        python.responder = body -> new FakeListingContextServer.Reply(200, FakeListingContextServer.success(body,
                "COMPLETED", FakeListingContextServer.completedFields()).body(), 0, 4000);
        long started = System.nanoTime();
        assertThat(analyze()).isEqualTo(new ListingContextClient.Failure("ANALYZER_TIMEOUT", null, null));
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(3000));
    }

    @Test
    void oversizedInputIsNotSent() {
        ListingContextClient.Result result = client(true, python.baseUrl(), FakeListingContextServer.INTERNAL_KEY)
                .analyze(UUID.randomUUID(), UUID.randomUUID(), SHA, "image/png",
                        new byte[(int) ListingContextClient.MAX_IMAGE_BYTES + 1]);
        assertThat(result).isEqualTo(new ListingContextClient.Failure("ANALYSIS_INPUT_TOO_LARGE", null, null));
        assertThat(python.received).isEmpty();
    }

    @Test
    void contractViolationsAreRejectedInsteadOfStoredAsResults() {
        assertInvalid(body -> mutate(body, root -> root.put("extra", "x")));
        assertInvalid(body -> mutate(body, root -> root.put("reviewRequired", false)));
        assertInvalid(body -> mutate(body, root -> root.put("status", "FAILED")));
        assertInvalid(body -> mutate(body, root -> root.put("errorCode", "MODEL_TIMEOUT")));
        assertInvalid(body -> mutate(body, root -> root.put("analyzedAt", "yesterday")));
        assertInvalid(body -> mutate(body, root -> root.put("modelName", "")));
        assertInvalid(body -> mutate(body, root -> root.remove("modelVersion")));
        assertInvalid(body -> fields(body, fields -> fields.remove("seat")));
        assertInvalid(body -> fields(body, fields -> fields.put("saleText",
                FakeListingContextServer.observation("FOUND", "양도", null, null, List.of()))));
        assertInvalid(body -> fields(body, fields -> fields.put("seat",
                FakeListingContextServer.observation("UNKNOWN", null, "12", "ABSENT", List.of()))));
        assertInvalid(body -> fields(body, fields -> fields.put("zone",
                FakeListingContextServer.observation("AMBIGUOUS", null, "A", null, List.of("A")))));
        assertInvalid(body -> fields(body, fields -> fields.put("zone",
                FakeListingContextServer.observation("AMBIGUOUS", null, "A", null, List.of("A", "A")))));
        assertInvalid(body -> fields(body, fields -> fields.put("askingPrice",
                FakeListingContextServer.observation("FOUND", Map.of("raw", "45만원", "amount", "4.5e5"), "45만원",
                        null, List.of()))));
        assertInvalid(body -> fields(body, fields -> fields.put("eventName",
                FakeListingContextServer.observation("FOUND", "x".repeat(257), "x".repeat(257), null, List.of()))));
        assertInvalid(body -> {
            String valid = FakeListingContextServer.success(body, "COMPLETED",
                    FakeListingContextServer.completedFields()).body();
            return new FakeListingContextServer.Reply(200, valid.replaceFirst("\\{", "{\"status\":\"PARTIAL\","), 0);
        });
        python.responder = body -> new FakeListingContextServer.Reply(200, "x".repeat(300 * 1024), 0);
        assertThat(analyze()).isEqualTo(new ListingContextClient.Failure("ANALYZER_RESPONSE_INVALID", 200, null));
        python.responder = body -> mutate(body, root -> root.put("imageSha256", "b".repeat(64)));
        assertThat(analyze()).isEqualTo(new ListingContextClient.Failure("ANALYZER_IDENTITY_MISMATCH", 200, null));
    }

    @Test
    void problemResponsesKeepOnlyTrustedAnalyzerCodes() {
        python.responder = body -> FakeListingContextServer.problem(415, "IMAGE_MIME_MISMATCH", body);
        assertThat(analyze()).isEqualTo(new ListingContextClient.Failure("ANALYZER_INPUT_REJECTED", 415,
                "IMAGE_MIME_MISMATCH"));
        python.responder = body -> {
            Map<String, Object> other = new LinkedHashMap<>(body);
            other.put("requestId", UUID.randomUUID().toString());
            return FakeListingContextServer.problem(500, "ANALYSIS_INTERNAL_ERROR", other);
        };
        assertThat(analyze()).isEqualTo(new ListingContextClient.Failure("ANALYZER_INTERNAL_ERROR", 500, null));
        python.responder = body -> new FakeListingContextServer.Reply(503, "<html>secret stack</html>", 0);
        assertThat(analyze()).isEqualTo(new ListingContextClient.Failure("ANALYZER_UNAVAILABLE", 503, null));
    }

    private void assertInvalid(Function<Map<String, Object>, FakeListingContextServer.Reply> responder) {
        python.responder = responder;
        assertThat(analyze()).isEqualTo(new ListingContextClient.Failure("ANALYZER_RESPONSE_INVALID", 200, null));
    }

    private ListingContextClient.Result analyze() {
        return client(true, python.baseUrl(), FakeListingContextServer.INTERNAL_KEY)
                .analyze(UUID.randomUUID(), UUID.randomUUID(), SHA, "image/png", IMAGE);
    }

    @SuppressWarnings("unchecked")
    private static FakeListingContextServer.Reply mutate(Map<String, Object> request,
            java.util.function.Consumer<Map<String, Object>> change) {
        Map<String, Object> root = FakeListingContextServer.JSON.readValue(FakeListingContextServer.success(request,
                "COMPLETED", FakeListingContextServer.completedFields()).body(), LinkedHashMap.class);
        change.accept(root);
        return new FakeListingContextServer.Reply(200, FakeListingContextServer.JSON.writeValueAsString(root), 0);
    }

    @SuppressWarnings("unchecked")
    private static FakeListingContextServer.Reply fields(Map<String, Object> request,
            java.util.function.Consumer<Map<String, Object>> change) {
        return mutate(request, root -> change.accept((Map<String, Object>) root.get("fields")));
    }

    private static ListingContextClient client(boolean enabled, String url, String key) {
        return new ListingContextClient(enabled, url, key, Duration.ofSeconds(1), Duration.ofSeconds(1));
    }
}
