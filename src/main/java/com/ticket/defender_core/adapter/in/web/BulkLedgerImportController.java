package com.ticket.defender_core.adapter.in.web;

import com.ticket.defender_core.adapter.in.web.dto.BulkLedgerImportDto;
import com.ticket.defender_core.application.service.BulkLedgerImportService;
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
@RequestMapping("/api/v1/admin/ledger-imports")
@RequiredArgsConstructor
public class BulkLedgerImportController {
    private final BulkLedgerImportService service;

    @PostMapping
    public ResponseEntity<BulkLedgerImportDto.View> register(@RequestHeader("Idempotency-Key") String key,
            @RequestBody BulkLedgerImportDto.RegisterRequest input) {
        return ResponseEntity.status(HttpStatus.CREATED).header("Cache-Control", "no-store")
                .body(service.register(key, input));
    }

    @GetMapping
    public ResponseEntity<BulkLedgerImportDto.Page<BulkLedgerImportDto.View>> list(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.list(page, pageSize));
    }

    @GetMapping("/{importId}")
    public ResponseEntity<BulkLedgerImportDto.View> detail(@PathVariable UUID importId) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.detail(importId));
    }

    @GetMapping("/{importId}/errors")
    public ResponseEntity<BulkLedgerImportDto.Page<BulkLedgerImportDto.Error>> errors(@PathVariable UUID importId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "100") int pageSize) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.errors(importId, page, pageSize));
    }

    @PostMapping("/{importId}/cancel")
    public ResponseEntity<BulkLedgerImportDto.View> cancel(@PathVariable UUID importId) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.cancel(importId));
    }
}
