package com.ticket.defender_core.integration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.ticket.defender_core.adapter.out.persistence.FanReportRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantEventRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantRepository;
import com.ticket.defender_core.adapter.out.persistence.TicketAuditRepository;
import com.ticket.defender_core.application.service.EvidenceObjectStorage;
import com.ticket.defender_core.domain.AccessRecordStatus;
import com.ticket.defender_core.domain.FanReport;
import com.ticket.defender_core.domain.Tenant;
import com.ticket.defender_core.domain.TenantEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:fan-report-http;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "ticket-defender.fan-report.enabled=true",
        "ticket-defender.fan-report.hmac-key-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "ticket-defender.fan-report.storage.endpoint=http://127.0.0.1:19000",
        "ticket-defender.fan-report.storage.bucket=synthetic-private-bucket",
        "ticket-defender.fan-report.storage.access-key=synthetic-key",
        "ticket-defender.fan-report.storage.secret-key=synthetic-secret"
})
@AutoConfigureMockMvc
class FanReportIntakeHttpTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TenantRepository tenants;
    @Autowired private TenantEventRepository events;
    @Autowired private FanReportRepository reports;
    @Autowired private TicketAuditRepository audits;
    @MockitoBean private EvidenceObjectStorage storage;
    private UUID tenantId;
    private UUID eventId;

    @BeforeEach
    void seed() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS public_request_quota (scope VARCHAR(16) NOT NULL, "
                + "actor_digest CHAR(64) NOT NULL, window_started_at TIMESTAMP NOT NULL, "
                + "attempts INT NOT NULL, PRIMARY KEY (scope, actor_digest))");
        jdbc.execute("DELETE FROM public_request_quota");
        tenantId = UUID.randomUUID();
        eventId = UUID.randomUUID();
        tenants.save(new Tenant(tenantId, "Synthetic tenant", AccessRecordStatus.ACTIVE));
        TenantEvent event = new TenantEvent(eventId, tenantId, "public-ref", "Shared title", AccessRecordStatus.ACTIVE);
        event.setPublicIntakeEnabled(true);
        events.saveAndFlush(event);
    }

    @Test
    void acceptsNoImageAndLooksUpOnlyReceiptState() throws Exception {
        long auditCount = audits.count();
        JsonNode receipt = json.readTree(mvc.perform(multipart("/api/v1/fan-reports")
                        .file(reportPart(eventId, false)).header("Idempotency-Key", "synthetic-idempotency-001"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(receipt.path("intakeStatus").asText()).isEqualTo("RECEIVED");
        assertThat(receipt.path("lookupCode").asText()).hasSizeGreaterThan(40);
        assertThat(receipt.has("tenantId")).isFalse();
        assertThat(receipt.has("objectKey")).isFalse();
        FanReport stored = reports.findById(UUID.fromString(receipt.path("fanReportId").asText())).orElseThrow();
        assertThat(stored.getTenantId()).isEqualTo(tenantId);
        assertThat(stored.getEventId()).isEqualTo(eventId);
        assertThat(stored.getVerifiedPerformanceRef()).isNull();
        assertThat(audits.count()).isEqualTo(auditCount);
        verify(storage, never()).put(anyString(), any());

        String lookup = "{\"receiptNumber\":\"" + receipt.path("receiptNumber").asText()
                + "\",\"lookupCode\":\"" + receipt.path("lookupCode").asText() + "\"}";
        String body = mvc.perform(post("/api/v1/fan-reports/lookup").contentType(MediaType.APPLICATION_JSON)
                        .content(lookup)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("RECEIVED").doesNotContain("tenantId", "objectKey", "eventId");
        mvc.perform(post("/api/v1/fan-reports/lookup").contentType(MediaType.APPLICATION_JSON)
                        .content(lookup.replace(receipt.path("lookupCode").asText(), "wrong-code")))
                .andExpect(status().isNotFound());
    }

    @Test
    void storesPngAndReplaysWithoutSecondLookupCode() throws Exception {
        when(storage.put(anyString(), any())).thenReturn("etag-synthetic");
        JsonNode first = json.readTree(mvc.perform(multipart("/api/v1/fan-reports")
                        .file(reportPart(eventId, false)).file(png())
                        .header("Idempotency-Key", "synthetic-idempotency-002"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        JsonNode replay = json.readTree(mvc.perform(multipart("/api/v1/fan-reports")
                        .file(reportPart(eventId, false)).file(png())
                        .header("Idempotency-Key", "synthetic-idempotency-002"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(replay.path("receiptNumber").asText()).isEqualTo(first.path("receiptNumber").asText());
        assertThat(replay.path("lookupCode").isNull()).isTrue();
        verify(storage, times(1)).put(anyString(), any());
        assertThat(reports.count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void missingOrClosedEventAndForgedTenantAreRejected() throws Exception {
        mvc.perform(multipart("/api/v1/fan-reports").file(reportPart(UUID.randomUUID(), false))
                        .header("Idempotency-Key", "synthetic-idempotency-003"))
                .andExpect(status().isNotFound());
        TenantEvent event = events.findById(eventId).orElseThrow();
        event.setPublicIntakeEnabled(false);
        events.saveAndFlush(event);
        mvc.perform(multipart("/api/v1/fan-reports").file(reportPart(eventId, false))
                        .header("Idempotency-Key", "synthetic-idempotency-004"))
                .andExpect(status().isNotFound());
        event.setPublicIntakeEnabled(true);
        events.saveAndFlush(event);
        mvc.perform(multipart("/api/v1/fan-reports").file(reportPart(eventId, true))
                        .header("Idempotency-Key", "synthetic-idempotency-005"))
                .andExpect(status().isBadRequest());
        verify(storage, never()).put(anyString(), any());
    }

    private MockMultipartFile reportPart(UUID event, boolean forgedTenant) {
        String body = "{\"eventId\":\"" + event + "\",\"performanceAt\":\"2026-11-01T19:30:00\","
                + "\"reportType\":\"OTHER\",\"description\":\"A synthetic suspected listing description\""
                + (forgedTenant ? ",\"tenantId\":\"" + UUID.randomUUID() + "\"" : "") + "}";
        return new MockMultipartFile("report", "", MediaType.APPLICATION_JSON_VALUE,
                body.getBytes(StandardCharsets.UTF_8));
    }

    private MockMultipartFile png() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return new MockMultipartFile("evidence", "synthetic.png", "image/png", bytes.toByteArray());
    }
}
