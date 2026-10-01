package com.ticket.defender_core.adapter.in.web;

import com.ticket.defender_core.application.service.AuditReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/audits")
@RequiredArgsConstructor
public class AuditReportController {

    private final AuditReportService auditReportService;

    /**
     * 특정 적발 내역(auditId)의 암표 탐지 보고서(PDF)를 생성하여 반환합니다.
     */
    @GetMapping("/{auditId}/report")
    public ResponseEntity<byte[]> downloadAuditReport(@PathVariable Long auditId) {
        log.info("요청 수신: PDF 공식 소명 보고서 발급 (auditId={})", auditId);

        byte[] pdfBytes = auditReportService.issueAuditReport(auditId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);

        String filename = "ticket_defender_report_" + auditId + ".pdf";
        headers.setContentDispositionFormData("inline", filename);

        headers.setCacheControl("must-revalidate, post-check=0, pre-check=0");

        return new ResponseEntity<>(pdfBytes, headers, HttpStatus.OK);
    }
}