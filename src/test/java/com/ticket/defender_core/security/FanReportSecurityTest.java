package com.ticket.defender_core.security;

import com.ticket.defender_core.adapter.in.web.dto.FanReportLookupResponse;
import com.ticket.defender_core.adapter.in.web.dto.FanReportReceipt;
import com.ticket.defender_core.application.service.FanReportIntakeService;
import com.ticket.defender_core.application.service.PublicRequestLimiter;
import com.ticket.defender_core.domain.FanReport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:fan-report-security;MODE=MySQL;DB_CLOSE_DELAY=-1",
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
class FanReportSecurityTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private FanReportIntakeService intake;
    @MockitoBean private PublicRequestLimiter limiter;

    @Test
    void onlyExactPublicPostPathsAreAnonymousAndResponsesHideInternalData() throws Exception {
        UUID id = UUID.randomUUID();
        when(intake.submit(any(), nullable(java.util.List.class), anyString(), anyString())).thenReturn(new FanReportReceipt(
                id, "FR-synthetic", "one-time-synthetic-code", FanReport.IntakeStatus.RECEIVED,
                LocalDateTime.parse("2026-10-20T12:00:00").atOffset(java.time.ZoneOffset.UTC)));
        MockMultipartFile report = new MockMultipartFile("report", "", MediaType.APPLICATION_JSON_VALUE,
                ("{\"eventId\":\"" + id + "\",\"performanceAt\":\"2026-11-01T19:30:00\","
                        + "\"reportType\":\"OTHER\",\"description\":\"A synthetic suspected listing description\"}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/v1/fan-reports").file(report)
                        .header("Idempotency-Key", "99999999-9999-4999-8999-999999999999")
                        .header("Report-Recovery-Secret", "A".repeat(43)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.receiptNumber").value("FR-synthetic"))
                .andExpect(content().string(not(containsString("tenantId"))))
                .andExpect(content().string(not(containsString("objectKey"))));
        when(intake.lookup(any())).thenReturn(new FanReportLookupResponse(FanReport.IntakeStatus.RECEIVED,
                LocalDateTime.parse("2026-10-20T12:00:00").atOffset(java.time.ZoneOffset.UTC)));
        mvc.perform(post("/api/v1/fan-reports/lookup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receiptNumber\":\"FR-synthetic\",\"lookupCode\":\"secret\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("tenantId"))))
                .andExpect(content().string(not(containsString("objectKey"))));
        mvc.perform(get("/api/v1/fan-reports")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/audits/1/report")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/track1/clusters")).andExpect(status().isUnauthorized());
    }

    @Test
    void extraClientTenantFieldCannotEstablishScope() throws Exception {
        MockMultipartFile report = new MockMultipartFile("report", "", MediaType.APPLICATION_JSON_VALUE,
                ("{\"eventId\":\"" + UUID.randomUUID() + "\",\"tenantId\":\"" + UUID.randomUUID()
                        + "\",\"performanceAt\":\"2026-11-01T19:30:00\",\"reportType\":\"OTHER\","
                        + "\"description\":\"A synthetic suspected listing description\"}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/v1/fan-reports").file(report)
                        .header("Idempotency-Key", "99999999-9999-4999-8999-999999999999")
                        .header("Report-Recovery-Secret", "A".repeat(43)))
                .andExpect(status().isBadRequest());
    }
}
