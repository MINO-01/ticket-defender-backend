package com.ticket.defender_core.adapter.in.web;

import com.ticket.defender_core.adapter.in.web.dto.Track1QuantityDto;
import com.ticket.defender_core.application.service.Track1QuantityAnalysisRunService;
import com.ticket.defender_core.application.service.Track1QuantityLedgerService;
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
@RequestMapping("/api/v1/admin/events/{eventId}/track1-quantity")
@RequiredArgsConstructor
public class Track1QuantityController {
    private final Track1QuantityLedgerService ledger;
    private final Track1QuantityAnalysisRunService runs;

    @PostMapping("/ledger-imports")
    public ResponseEntity<Track1QuantityDto.LedgerBatch> importLedger(@PathVariable UUID eventId,
            @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestBody Track1QuantityDto.ImportRequest input) {
        return ResponseEntity.status(HttpStatus.CREATED).header("Cache-Control", "no-store")
                .body(ledger.importLedger(eventId, idempotencyKey, input));
    }

    @GetMapping("/ledger-imports")
    public ResponseEntity<Track1QuantityDto.Page<Track1QuantityDto.LedgerBatch>> history(@PathVariable UUID eventId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(ledger.history(eventId, page, pageSize));
    }

    @PostMapping("/analysis-runs")
    public ResponseEntity<Track1QuantityDto.AnalysisRun> start(@PathVariable UUID eventId,
            @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestBody Track1QuantityDto.RunRequest input) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).header("Cache-Control", "no-store")
                .body(runs.start(eventId, idempotencyKey, input));
    }

    @GetMapping("/analysis-runs")
    public ResponseEntity<Track1QuantityDto.Page<Track1QuantityDto.AnalysisRun>> list(@PathVariable UUID eventId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(runs.list(eventId, page, pageSize));
    }

    @GetMapping("/analysis-runs/{runId}")
    public ResponseEntity<Track1QuantityDto.AnalysisRun> get(@PathVariable UUID eventId, @PathVariable UUID runId) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(runs.get(eventId, runId));
    }
}
