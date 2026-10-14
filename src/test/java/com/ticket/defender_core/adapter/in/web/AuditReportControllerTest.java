package com.ticket.defender_core.adapter.in.web;

import com.ticket.defender_core.application.service.AuditReportService;
import com.ticket.defender_core.application.service.AuditReportIssuanceDisabledException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class AuditReportControllerTest {

    private MockMvc mockMvc;

    @Mock
    private AuditReportService auditReportService;

    @InjectMocks
    private AuditReportController auditReportController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(auditReportController).build();
    }

    @Test
    @DisplayName("공식 PDF 발급 요청은 식별자와 내부 정보를 노출하지 않는 409 Problem Detail로 응답한다")
    void downloadAuditReport_ReturnsIssuanceDisabledProblem() throws Exception {
        // given
        Long auditId = 987654321L;

        when(auditReportService.issueAuditReport(auditId))
                .thenThrow(new AuditReportIssuanceDisabledException());

        // when & then
        mockMvc.perform(post("/api/v1/audits/{auditId}/report", auditId))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_DISPOSITION))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.instance").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value(AuditReportIssuanceDisabledException.ERROR_CODE))
                .andExpect(jsonPath("$.detail").value(
                        "감사 보고서 발급 기능은 승인 절차 구축 전까지 일시적으로 제공되지 않습니다."))
                .andExpect(content().string(not(containsString("%PDF-"))))
                .andExpect(content().string(not(containsString(auditId.toString()))))
                .andExpect(content().string(not(containsString("reservation-secret"))))
                .andExpect(content().string(not(containsString("AuditReportIssuanceDisabledException"))))
                .andExpect(content().string(not(containsString("at com.ticket"))));

        verify(auditReportService).issueAuditReport(auditId);
    }
}
