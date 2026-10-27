package com.ticket.defender_core.adapter.in.web;

import com.ticket.defender_core.adapter.in.web.dto.EvidenceIntegrityDto;
import com.ticket.defender_core.application.service.EvidenceIntegrityService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cases/{caseId}")
@RequiredArgsConstructor
public class EvidenceIntegrityController {
    private final EvidenceIntegrityService service;

    @PostMapping("/evidence-verifications")
    public ResponseEntity<EvidenceIntegrityDto.Verification> verify(@PathVariable UUID caseId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody EvidenceIntegrityDto.VerifyInput input) {
        return ResponseEntity.status(HttpStatus.CREATED).header("Cache-Control", "no-store")
                .body(service.verify(caseId, input, key));
    }

    @GetMapping("/evidence-verifications")
    public ResponseEntity<EvidenceIntegrityDto.VerificationPage> records(@PathVariable UUID caseId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(service.records(caseId, page, pageSize));
    }

    @GetMapping("/integrity-evaluation")
    public ResponseEntity<EvidenceIntegrityDto.Evaluation> evaluate(@PathVariable UUID caseId,
            @RequestParam UUID reportVersionId) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(service.evaluate(caseId, reportVersionId));
    }
}
