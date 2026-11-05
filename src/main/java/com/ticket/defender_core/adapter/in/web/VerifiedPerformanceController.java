package com.ticket.defender_core.adapter.in.web;

import com.ticket.defender_core.adapter.in.web.dto.CandidatePipelineDto;
import com.ticket.defender_core.application.service.VerifiedPerformanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/events/{eventId}/verified-performances")
@RequiredArgsConstructor
public class VerifiedPerformanceController {
    private final VerifiedPerformanceService service;

    @PostMapping
    public ResponseEntity<CandidatePipelineDto.Performance> create(@PathVariable UUID eventId,
            @RequestBody CandidatePipelineDto.CreatePerformance input) {
        return ResponseEntity.status(HttpStatus.CREATED).header("Cache-Control", "no-store")
                .body(service.create(eventId, input));
    }

    @PatchMapping("/{performanceId}")
    public ResponseEntity<CandidatePipelineDto.Performance> revise(@PathVariable UUID eventId,
            @PathVariable UUID performanceId, @RequestBody CandidatePipelineDto.RevisePerformance input) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(service.revise(eventId, performanceId, input));
    }

    @PostMapping("/{performanceId}/withdrawals")
    public ResponseEntity<CandidatePipelineDto.Performance> withdraw(@PathVariable UUID eventId,
            @PathVariable UUID performanceId, @RequestBody CandidatePipelineDto.WithdrawPerformance input) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(service.withdraw(eventId, performanceId, input));
    }

    @GetMapping
    public ResponseEntity<List<CandidatePipelineDto.Performance>> list(@PathVariable UUID eventId) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.list(eventId));
    }
}
