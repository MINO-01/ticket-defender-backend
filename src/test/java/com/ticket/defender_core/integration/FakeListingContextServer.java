package com.ticket.defender_core.integration;

import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;

/** 합성 응답만 반환하는 Python Listing Context API 대역입니다. 외부 모델이나 실제 이미지를 사용하지 않습니다. */
final class FakeListingContextServer implements AutoCloseable {
    static final String INTERNAL_KEY = "synthetic-track2-internal-key-0123456789abcdef";
    static final ObjectMapper JSON = JsonMapper.builder().build();
    static final List<String> FIELDS = List.of("eventName", "performanceAt", "platformDisplayName", "askingPrice",
            "currency", "saleText", "zone", "row", "seat");

    record Received(Map<String, String> headers, Map<String, Object> body) { }
    record Reply(int status, String body, long delayMillis) { }

    private final HttpServer server;
    final List<Received> received = new CopyOnWriteArrayList<>();
    volatile Function<Map<String, Object>, Reply> responder = body -> success(body, "COMPLETED", completedFields());

    FakeListingContextServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/api/v1/parse/listing-context", exchange -> {
            Map<String, String> headers = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(), values.getFirst()));
            byte[] raw;
            try (InputStream input = exchange.getRequestBody()) {
                raw = input.readAllBytes();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> body = JSON.readValue(raw, Map.class);
            received.add(new Received(headers, body));
            Reply reply = INTERNAL_KEY.equals(headers.get("x-td-internal-key")) ? responder.apply(body)
                    : problem(401, "INTERNAL_AUTH_REQUIRED", body);
            if (reply.delayMillis() > 0) {
                try {
                    Thread.sleep(reply.delayMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] out = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type",
                    reply.status() == 200 ? "application/json" : "application/problem+json");
            try {
                exchange.sendResponseHeaders(reply.status(), out.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(out);
                }
            } catch (IOException clientGone) {
                exchange.close();
            }
        });
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    static Reply success(Map<String, Object> request, String status, Map<String, Object> fields) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", request.get("requestId"));
        body.put("evidenceId", request.get("evidenceId"));
        body.put("imageSha256", request.get("imageSha256"));
        body.put("status", status);
        body.put("fields", fields);
        body.put("modelName", "fake-test-provider");
        body.put("modelVersion", "test-v1");
        body.put("analyzedAt", "2026-10-22T00:00:00Z");
        body.put("reviewRequired", true);
        body.put("errorCode", null);
        return new Reply(200, JSON.writeValueAsString(body), 0);
    }

    static Reply problem(int status, String code, Map<String, Object> request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", "Analysis unavailable");
        body.put("status", status);
        body.put("code", code);
        body.put("detail", "Analysis is unavailable");
        body.put("requestId", request.get("requestId"));
        body.put("analysisStatus", "FAILED");
        return new Reply(status, JSON.writeValueAsString(body), 0);
    }

    static Map<String, Object> unknownFields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        FIELDS.forEach(name -> fields.put(name, unknown("ABSENT")));
        return fields;
    }

    static Map<String, Object> completedFields() {
        Map<String, Object> fields = unknownFields();
        fields.put("saleText", found("양도합니다", "양도합니다"));
        fields.put("eventName", found("가상 콘서트", "가상 콘서트 양도"));
        fields.put("askingPrice", observation("FOUND", Map.of("raw", "45만원", "amount", "450000"), "45만원", null,
                List.of()));
        fields.put("zone", observation("AMBIGUOUS", null, "A구역 또는 B구역", null, List.of("A", "B")));
        fields.put("row", unknown("UNREADABLE"));
        fields.put("seat", unknown("NOT_CONFIRMED"));
        return fields;
    }

    static Map<String, Object> found(String value, String quote) {
        return observation("FOUND", value, quote, null, List.of());
    }

    static Map<String, Object> unknown(String reason) {
        return observation("UNKNOWN", null, null, reason, List.of());
    }

    static Map<String, Object> observation(String state, Object value, String quote, String reason,
            List<String> candidates) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("state", state);
        field.put("value", value);
        field.put("evidenceQuote", quote);
        field.put("unknownReason", reason);
        field.put("candidates", candidates);
        return field;
    }
}
