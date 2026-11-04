package com.ticket.defender_core.adapter.in.web;

import com.ticket.defender_core.adapter.in.web.dto.CandidatePipelineDto;
import com.ticket.defender_core.application.service.ReservationImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/events/{eventId}/reservation-imports")
@RequiredArgsConstructor
public class ReservationImportController {
    private final ReservationImportService service;

    @PostMapping
    public ResponseEntity<CandidatePipelineDto.ImportResult> importSnapshot(@PathVariable UUID eventId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CandidatePipelineDto.ImportReservations input) {
        return ResponseEntity.status(HttpStatus.CREATED).header("Cache-Control", "no-store")
                .body(service.importSnapshot(eventId, idempotencyKey, input));
    }
}
