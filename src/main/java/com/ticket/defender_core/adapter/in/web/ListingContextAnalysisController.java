package com.ticket.defender_core.adapter.in.web;

import com.ticket.defender_core.adapter.in.web.dto.ListingContextAnalysisDto;
import com.ticket.defender_core.application.service.ListingContextAnalysisService;
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
@RequestMapping("/api/v1/admin/fan-reports/{fanReportId}/evidence/{evidenceId}/listing-analyses")
@RequiredArgsConstructor
public class ListingContextAnalysisController {
    private final ListingContextAnalysisService service;

    @PostMapping
    public ResponseEntity<ListingContextAnalysisDto.View> analyze(@PathVariable UUID fanReportId,
            @PathVariable UUID evidenceId, @RequestHeader("Idempotency-Key") String key) {
        return ResponseEntity.status(HttpStatus.CREATED).header("Cache-Control", "no-store")
                .body(service.analyze(fanReportId, evidenceId, key));
    }

    @GetMapping
    public ResponseEntity<ListingContextAnalysisDto.Page> history(@PathVariable UUID fanReportId,
            @PathVariable UUID evidenceId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(service.history(fanReportId, evidenceId, page, pageSize));
    }
}
