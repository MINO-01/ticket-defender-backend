package com.ticket.defender_core.adapter.in.web;

import com.ticket.defender_core.application.service.InvestigationTargetService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** PR 7 참조번호 열람 API. 원본은 권한 확인 뒤 no-store 응답으로만 반환합니다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/events/{eventId}")
public class InvestigationTargetController {
    private final InvestigationTargetService targets;

    @GetMapping("/multi-event/{runId}/candidates/{candidateId}/targets")
    public ResponseEntity<InvestigationTargetService.TargetPage> multiEventTargets(@PathVariable UUID eventId,
            @PathVariable UUID runId, @PathVariable String candidateId,
            @RequestHeader("X-Investigation-Reason") String reason, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        return noStore(targets.multiEventTargets(eventId, runId, candidateId, page, pageSize, reason));
    }

    @PostMapping("/multi-event/{runId}/candidates/{candidateId}/targets/reveal")
    public ResponseEntity<InvestigationTargetService.RevealedPage> revealMultiEvent(@PathVariable UUID eventId,
            @PathVariable UUID runId, @PathVariable String candidateId, @RequestBody ReasonRequest request,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int pageSize) {
        return noStore(targets.revealMultiEvent(eventId, runId, candidateId, page, pageSize, request == null ? null : request.reason()));
    }

    @PostMapping("/multi-event/{runId}/candidates/{candidateId}/targets/export")
    public ResponseEntity<byte[]> exportMultiEvent(@PathVariable UUID eventId, @PathVariable UUID runId,
            @PathVariable String candidateId, @RequestBody ReasonRequest request) {
        return csv(targets.exportMultiEvent(eventId, runId, candidateId, request == null ? null : request.reason()));
    }

    @GetMapping("/ledger-track1/performances/{performanceId}/bulk-analysis-jobs/{jobId}/clusters/{clusterId}/targets")
    public ResponseEntity<InvestigationTargetService.TargetPage> bulkTargets(@PathVariable UUID eventId,
            @PathVariable UUID performanceId, @PathVariable UUID jobId, @PathVariable String clusterId,
            @RequestHeader("X-Investigation-Reason") String reason, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        return noStore(targets.bulkClusterTargets(eventId, performanceId, jobId, clusterId, page, pageSize, reason));
    }

    @PostMapping("/ledger-track1/performances/{performanceId}/bulk-analysis-jobs/{jobId}/clusters/{clusterId}/targets/reveal")
    public ResponseEntity<InvestigationTargetService.RevealedPage> revealBulk(@PathVariable UUID eventId,
            @PathVariable UUID performanceId, @PathVariable UUID jobId, @PathVariable String clusterId,
            @RequestBody ReasonRequest request, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        return noStore(targets.revealBulkCluster(eventId, performanceId, jobId, clusterId, page, pageSize,
                request == null ? null : request.reason()));
    }

    @PostMapping("/ledger-track1/performances/{performanceId}/bulk-analysis-jobs/{jobId}/clusters/{clusterId}/targets/export")
    public ResponseEntity<byte[]> exportBulk(@PathVariable UUID eventId, @PathVariable UUID performanceId,
            @PathVariable UUID jobId, @PathVariable String clusterId, @RequestBody ReasonRequest request) {
        return csv(targets.exportBulkCluster(eventId, performanceId, jobId, clusterId,
                request == null ? null : request.reason()));
    }

    @GetMapping("/investigation-reference-audits")
    public ResponseEntity<List<InvestigationTargetService.AuditEntry>> audit(@PathVariable UUID eventId) {
        return noStore(targets.audit(eventId));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }

    private static ResponseEntity<byte[]> csv(byte[] body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=investigation-targets.csv")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8)).body(body);
    }

    public record ReasonRequest(String reason) { }
}
