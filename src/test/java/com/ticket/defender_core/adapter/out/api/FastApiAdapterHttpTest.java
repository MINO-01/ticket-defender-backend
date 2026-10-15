package com.ticket.defender_core.adapter.out.api;

import com.sun.net.httpserver.HttpServer;
import com.ticket.defender_core.adapter.out.api.dto.MacroAnalysisRequest;
import com.ticket.defender_core.adapter.out.api.dto.MacroAnalysisResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FastApiAdapterHttpTest {

    private static final String REQUEST_ID = "2136558c-2326-4430-a46d-0793494e9b49";
    private static final String LOOPBACK_HOST = "127.0.0.1";

    @Test
    void successfulHttpResponseIsCompletedAnalysis() throws IOException {
        HttpServer server = server(200, response("COMPLETED", "LOUVAIN"));
        try {
            assertThat(server.getAddress().getAddress().getHostAddress()).isEqualTo(LOOPBACK_HOST);
            MacroAnalysisResponse analysis = adapter(server.getAddress().getPort()).requestMacroAnalysis(request());
            assertThat(analysis.requestId()).isEqualTo(REQUEST_ID);
            assertThat(analysis.status()).isEqualTo(MacroAnalysisResponse.Status.COMPLETED);
            assertThat(analysis.clusters()).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void connectionFailureIsNotACompletedAnalysis() throws IOException {
        int port;
        try (ServerSocket reserved = new ServerSocket(0, 1, InetAddress.getByName(LOOPBACK_HOST))) {
            port = reserved.getLocalPort();
        }

        FastApiAdapter adapter = adapter(port);
        assertThrows(ResourceAccessException.class, () -> adapter.requestMacroAnalysis(request()));
    }

    @Test
    void readTimeoutIsNotACompletedAnalysis() throws IOException {
        CountDownLatch received = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), 0), 0);
        server.createContext("/api/v1/clusters/analyze", exchange -> {
            received.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        try {
            RestClientException error = assertThrows(RestClientException.class,
                    () -> adapter(server.getAddress().getPort()).requestMacroAnalysis(request()));
            assertThat(received.getCount()).isZero();
            Throwable cause = error;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            assertThat(cause).isInstanceOf(SocketTimeoutException.class);
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void http500IsNotACompletedAnalysis() throws IOException {
        HttpServer server = server(500, "{\"detail\":\"Graph analysis failed\"}");
        try {
            assertThrows(RestClientException.class,
                    () -> adapter(server.getAddress().getPort()).requestMacroAnalysis(request()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void malformedJsonIsRejected() throws IOException {
        HttpServer server = server(200, "{");
        try {
            assertThrows(FastApiResponseContractException.class,
                    () -> adapter(server.getAddress().getPort()).requestMacroAnalysis(request()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mismatchedAlgorithmAndStatusAreRejected() throws IOException {
        for (String response : List.of(response("COMPLETED", "WCC"), response("UNAVAILABLE", "LOUVAIN"))) {
            HttpServer server = server(200, response);
            try {
                assertThrows(FastApiResponseContractException.class,
                        () -> adapter(server.getAddress().getPort()).requestMacroAnalysis(request()));
            } finally {
                server.stop(0);
            }
        }
    }

    private FastApiAdapter adapter(int port) {
        FastApiAdapter adapter = new FastApiAdapter(RestClient.builder());
        ReflectionTestUtils.setField(adapter, "fastApiUrl", "http://" + LOOPBACK_HOST + ":" + port);
        return adapter;
    }

    private MacroAnalysisRequest request() {
        return new MacroAnalysisRequest(REQUEST_ID, List.of(
                new MacroAnalysisRequest.Ticket("uid-1", "res-1", "event-1",
                        "a".repeat(64), null, null, null)
        ));
    }

    private String response(String status, String algorithm) {
        return """
                {"requestId":"%s","status":"%s","algorithm":"%s",
                 "algorithmVersion":"gds-2.6.6","analyzedAt":"2026-10-08T10:15:30Z","clusters":[]}
                """.formatted(REQUEST_ID, status, algorithm);
    }

    private HttpServer server(int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), 0), 0);
        server.createContext("/api/v1/clusters/analyze", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var stream = exchange.getResponseBody()) {
                stream.write(bytes);
            }
        });
        server.start();
        return server;
    }
}
