package com.ticket.defender_core.adapter.in.web;

import com.ticket.defender_core.adapter.in.web.dto.CasePreparationDto;
import com.ticket.defender_core.application.service.CasePreparationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/report-versions")
@RequiredArgsConstructor
public class ReportDraftController {
    private final CasePreparationService service;

    @GetMapping("/{reportVersionId}")
    public ResponseEntity<CasePreparationDto.Draft> detail(@PathVariable UUID reportVersionId) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(service.draft(reportVersionId));
    }
}
