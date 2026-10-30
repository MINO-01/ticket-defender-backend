package com.ticket.defender_core.adapter.in.web;

import com.ticket.defender_core.adapter.in.web.dto.OfficialPdfDto;
import com.ticket.defender_core.application.service.OfficialPdfIssuanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class OfficialPdfIssuanceController {
    private final OfficialPdfIssuanceService service;

    @GetMapping("/api/v1/approval-decisions/{approvalDecisionId}/official-pdf-eligibility")
    public ResponseEntity<OfficialPdfDto.Eligibility> eligibility(@PathVariable UUID approvalDecisionId) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.eligibility(approvalDecisionId));
    }

    @PostMapping("/api/v1/approval-decisions/{approvalDecisionId}/official-pdf-issuances")
    public ResponseEntity<OfficialPdfDto.Issuance> request(@PathVariable UUID approvalDecisionId,
            @RequestHeader("Idempotency-Key") String key) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).header("Cache-Control", "no-store")
                .body(service.request(approvalDecisionId, key));
    }

    @GetMapping("/api/v1/official-pdf-issuances/{issuanceId}")
    public ResponseEntity<OfficialPdfDto.Issuance> detail(@PathVariable UUID issuanceId) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.detail(issuanceId));
    }

    @PostMapping("/api/v1/official-pdf-issuances/{issuanceId}/retry")
    public ResponseEntity<OfficialPdfDto.Issuance> retry(@PathVariable UUID issuanceId,
            @RequestHeader("Idempotency-Key") String key) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).header("Cache-Control", "no-store")
                .body(service.retry(issuanceId, key));
    }

    @GetMapping("/api/v1/report-versions/{reportVersionId}/official-pdf-issuances")
    public ResponseEntity<List<OfficialPdfDto.Issuance>> history(@PathVariable UUID reportVersionId) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.history(reportVersionId));
    }

    @GetMapping("/api/v1/official-pdf-issuances/{issuanceId}/download")
    public ResponseEntity<byte[]> download(@PathVariable UUID issuanceId) {
        OfficialPdfIssuanceService.Download file = service.download(issuanceId);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).header("Cache-Control", "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("official-case-report-" + file.artifactId() + ".pdf").build().toString())
                .body(file.bytes());
    }
}
