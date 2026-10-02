package com.ticket.defender_core.adapter.in.web;

import com.ticket.defender_core.application.service.AuditReportService;
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
    @DisplayName("PDF 발급 요청 시 올바른 HTTP 헤더(application/pdf)와 바이너리 데이터를 반환해야 한다")
    void downloadAuditReport_ReturnsPdfWithCorrectHeaders() throws Exception {
        // given
        Long auditId = 100L;
        byte[] mockPdfBytes = "%PDF-1.4".getBytes();

        when(auditReportService.issueAuditReport(auditId)).thenReturn(mockPdfBytes);

        // when & then
        mockMvc.perform(post("/api/v1/audits/{auditId}/report", auditId))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE))
                .andExpect(header().exists(HttpHeaders.CONTENT_DISPOSITION))
                .andExpect(content().bytes(mockPdfBytes));
    }
}