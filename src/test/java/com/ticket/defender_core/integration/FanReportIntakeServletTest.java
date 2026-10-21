package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.out.persistence.TenantEventRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantRepository;
import com.ticket.defender_core.application.service.EvidenceObjectStorage;
import com.ticket.defender_core.domain.AccessRecordStatus;
import com.ticket.defender_core.domain.Tenant;
import com.ticket.defender_core.domain.TenantEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:fan-report-servlet;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.servlet.multipart.max-file-size=1KB",
        "spring.servlet.multipart.max-request-size=8KB",
        "ticket-defender.fan-report.enabled=true",
        "ticket-defender.fan-report.max-concurrent-intakes=1",
        "ticket-defender.fan-report.hmac-key-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "ticket-defender.fan-report.storage.endpoint=http://127.0.0.1:19000",
        "ticket-defender.fan-report.storage.bucket=synthetic-private-bucket",
        "ticket-defender.fan-report.storage.access-key=synthetic-key",
        "ticket-defender.fan-report.storage.secret-key=synthetic-secret"
})
class FanReportIntakeServletTest {
    private static final String RECOVERY_SECRET = "A".repeat(43);
    @Value("${local.server.port}") private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TenantRepository tenants;
    @Autowired private TenantEventRepository events;
    @Autowired private ObjectMapper json;
    @MockitoBean private EvidenceObjectStorage storage;
    private UUID eventId;

    @BeforeEach
    void seed() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS public_request_quota (scope VARCHAR(16) NOT NULL, "
                + "actor_digest CHAR(64) NOT NULL, window_started_at TIMESTAMP NOT NULL, "
                + "attempts INT NOT NULL, PRIMARY KEY (scope, actor_digest))");
        jdbc.execute("DELETE FROM public_request_quota");
        UUID tenantId = UUID.randomUUID();
        eventId = UUID.randomUUID();
        tenants.save(new Tenant(tenantId, "Synthetic tenant", AccessRecordStatus.ACTIVE));
        TenantEvent event = new TenantEvent(eventId, tenantId, "ref", "Synthetic event",
                AccessRecordStatus.ACTIVE);
        event.setPublicIntakeEnabled(true);
        events.saveAndFlush(event);
    }

    @Test
    void realServletLimitReturnsScopedProblemDetailBeforeControllerSelection() throws Exception {
        HttpResponse<String> response = send(request(UUID.randomUUID(), new byte[2048]));
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.headers().firstValue("Cache-Control").orElse(""))
                .contains("no-store");
        var problem = json.readTree(response.body());
        assertThat(problem.path("status").asInt()).isEqualTo(413);
        assertThat(problem.path("code").asText()).isEqualTo("REQUEST_TOO_LARGE");
        assertThat(problem.path("requestId").asText()).isNotBlank();

        HttpRequest admin = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/audits/123/report"))
                .header("Content-Type", "multipart/form-data; boundary=synthetic")
                .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[2048])).build();
        assertThat(send(admin).statusCode()).isEqualTo(401);
    }

    @Test
    void admissionRejectsBeforeMultipartParsingAndReleasesAfterStorage() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("synthetic wait timeout");
            return "etag-synthetic";
        }).when(storage).put(anyString(), any());

        CompletableFuture<HttpResponse<String>> first = CompletableFuture.supplyAsync(() -> {
            try { return send(request(UUID.randomUUID(), png())); }
            catch (Exception error) { throw new IllegalStateException(error); }
        });
        try {
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            // This body exceeds the servlet limit. INTAKE_BUSY proves the filter ran before parsing.
            HttpResponse<String> busy = send(request(UUID.randomUUID(), new byte[2048]));
            assertThat(busy.statusCode()).isEqualTo(503);
            assertThat(busy.headers().firstValue("Content-Type").orElse(""))
                    .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            assertThat(busy.headers().firstValue("Cache-Control").orElse(""))
                    .contains("no-store");
            assertThat(json.readTree(busy.body()).path("code").asText()).isEqualTo("INTAKE_BUSY");
            assertThat(json.readTree(busy.body()).path("requestId").asText()).isNotBlank();
        } finally {
            release.countDown();
        }
        assertThat(first.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
        assertThat(send(request(UUID.randomUUID(), null)).statusCode()).isEqualTo(201);
    }

    @Test
    void invalidImageDoesNotKeepAdmissionSlot() throws Exception {
        assertThat(send(request(UUID.randomUUID(), new byte[]{1, 2, 3})).statusCode()).isEqualTo(415);
        assertThat(send(request(UUID.randomUUID(), null)).statusCode()).isEqualTo(201);
    }

    private HttpRequest request(UUID key, byte[] evidence) throws Exception {
        String boundary = "synthetic-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        String report = "{\"eventId\":\"" + eventId + "\",\"performanceAt\":\"2026-11-01T19:30:00\","
                + "\"reportType\":\"OTHER\",\"description\":\"A synthetic suspected listing description\"}";
        part(body, boundary, "report", "", MediaType.APPLICATION_JSON_VALUE,
                report.getBytes(StandardCharsets.UTF_8));
        if (evidence != null) part(body, boundary, "evidence", "synthetic.png", "image/png", evidence);
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/fan-reports"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Idempotency-Key", key.toString())
                .header("Report-Recovery-Secret", RECOVERY_SECRET)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
    }

    private static void part(ByteArrayOutputStream out, String boundary, String name,
                             String filename, String mediaType, byte[] bytes) throws Exception {
        String disposition = "Content-Disposition: form-data; name=\"" + name + "\""
                + (filename.isEmpty() ? "" : "; filename=\"" + filename + "\"");
        out.write(("--" + boundary + "\r\n" + disposition + "\r\nContent-Type: " + mediaType
                + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }

    private String baseUrl() { return "http://127.0.0.1:" + port; }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
