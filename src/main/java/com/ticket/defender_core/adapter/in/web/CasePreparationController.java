package com.ticket.defender_core.adapter.in.web;

import com.ticket.defender_core.adapter.in.web.dto.CasePreparationDto;
import com.ticket.defender_core.application.service.CasePreparationService;
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
public class CasePreparationController {
    private final CasePreparationService service;

    @PostMapping("/evidence-snapshots")
    public ResponseEntity<CasePreparationDto.Snapshot> capture(@PathVariable UUID caseId,
            @RequestHeader("Idempotency-Key") String key, @RequestBody CasePreparationDto.Capture input) {
        return ResponseEntity.status(HttpStatus.CREATED).header("Cache-Control", "no-store")
                .body(service.capture(caseId, input, key));
    }

    @GetMapping("/evidence-snapshots")
    public ResponseEntity<CasePreparationDto.SnapshotPage> snapshots(@PathVariable UUID caseId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(service.snapshots(caseId, page, pageSize));
    }

    @GetMapping("/evidence-snapshots/{snapshotId}")
    public ResponseEntity<CasePreparationDto.Snapshot> snapshot(@PathVariable UUID caseId,
            @PathVariable UUID snapshotId) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(service.snapshot(caseId, snapshotId));
    }

    @PostMapping("/report-versions")
    public ResponseEntity<CasePreparationDto.Draft> draft(@PathVariable UUID caseId,
            @RequestHeader("Idempotency-Key") String key, @RequestBody CasePreparationDto.DraftInput input) {
        return ResponseEntity.status(HttpStatus.CREATED).header("Cache-Control", "no-store")
                .body(service.draft(caseId, input, key));
    }

    @GetMapping("/report-versions")
    public ResponseEntity<CasePreparationDto.DraftPage> drafts(@PathVariable UUID caseId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(service.drafts(caseId, page, pageSize));
    }

}
