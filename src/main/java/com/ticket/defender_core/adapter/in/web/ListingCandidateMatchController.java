package com.ticket.defender_core.adapter.in.web;

import com.ticket.defender_core.adapter.in.web.dto.CandidatePipelineDto;
import com.ticket.defender_core.application.service.ListingCandidateMatchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/fan-reports/{fanReportId}/evidence/{evidenceId}/listing-analyses/{analysisId}/candidate-matches")
@RequiredArgsConstructor
public class ListingCandidateMatchController {
    private final ListingCandidateMatchService service;

    @PostMapping
    public ResponseEntity<CandidatePipelineDto.CandidateRun> match(@PathVariable UUID fanReportId,
            @PathVariable UUID evidenceId, @PathVariable UUID analysisId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return ResponseEntity.status(HttpStatus.CREATED).header("Cache-Control", "no-store")
                .body(service.match(fanReportId, evidenceId, analysisId, idempotencyKey));
    }

    @GetMapping
    public ResponseEntity<CandidatePipelineDto.CandidateRunPage> history(@PathVariable UUID fanReportId,
            @PathVariable UUID evidenceId, @PathVariable UUID analysisId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(service.history(fanReportId, evidenceId, analysisId, page, pageSize));
    }
}
