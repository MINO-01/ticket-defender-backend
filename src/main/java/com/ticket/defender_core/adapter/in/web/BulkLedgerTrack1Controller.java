package com.ticket.defender_core.adapter.in.web;

import com.ticket.defender_core.application.service.BulkLedgerTrack1SignalService;
import com.ticket.defender_core.application.service.BulkLedgerTrack1AnalysisQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/admin/events/{eventId}/ledger-track1")
public class BulkLedgerTrack1Controller {
    private final BulkLedgerTrack1SignalService signals;
    private final BulkLedgerTrack1AnalysisQueryService analyses;
    @PostMapping("/versions/{versionId}/signals")
    public ResponseEntity<Void> register(@PathVariable UUID eventId,@PathVariable UUID versionId,@RequestBody List<BulkLedgerTrack1SignalService.Signal> input){signals.register(eventId,versionId,input);return ResponseEntity.noContent().header("Cache-Control","no-store").build();}
    @GetMapping("/performances/{performanceId}/analysis-runs")
    public ResponseEntity<List<BulkLedgerTrack1AnalysisQueryService.View>> list(@PathVariable UUID eventId,@PathVariable UUID performanceId){return ResponseEntity.ok().header("Cache-Control","no-store").body(analyses.list(eventId,performanceId));}
    @GetMapping("/performances/{performanceId}/analysis-runs/{jobId}")
    public ResponseEntity<BulkLedgerTrack1AnalysisQueryService.View> get(@PathVariable UUID eventId,@PathVariable UUID performanceId,@PathVariable UUID jobId){return ResponseEntity.ok().header("Cache-Control","no-store").body(analyses.get(eventId,performanceId,jobId));}
}
