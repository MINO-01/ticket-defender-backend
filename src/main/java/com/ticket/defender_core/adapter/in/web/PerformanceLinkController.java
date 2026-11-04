package com.ticket.defender_core.adapter.in.web;

import com.ticket.defender_core.adapter.in.web.dto.CandidatePipelineDto;
import com.ticket.defender_core.application.service.PerformanceLinkService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/fan-reports/{fanReportId}/performance-link")
@RequiredArgsConstructor
public class PerformanceLinkController {
    private final PerformanceLinkService service;

    @PutMapping
    public ResponseEntity<CandidatePipelineDto.PerformanceLink> link(@PathVariable UUID fanReportId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CandidatePipelineDto.LinkPerformance input) {
        return ResponseEntity.status(HttpStatus.CREATED).header("Cache-Control", "no-store")
                .body(service.link(fanReportId, idempotencyKey, input));
    }

    @PostMapping("/withdrawals")
    public ResponseEntity<CandidatePipelineDto.PerformanceLink> withdraw(@PathVariable UUID fanReportId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CandidatePipelineDto.WithdrawLink input) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(service.withdraw(fanReportId, idempotencyKey, input));
    }

    @GetMapping
    public ResponseEntity<CandidatePipelineDto.PerformanceLinkHistory> history(@PathVariable UUID fanReportId) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.history(fanReportId));
    }
}
