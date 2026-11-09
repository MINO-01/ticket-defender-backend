package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.Track1CandidateLinkDto;
import com.ticket.defender_core.adapter.out.persistence.MacroAuditEvidenceReadRepository;
import com.ticket.defender_core.domain.CaseSourceReference;
import com.ticket.defender_core.domain.ListingCandidateMatchRun;
import com.ticket.defender_core.domain.MacroAuditEvidence;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Joins only already-linked Case sources.  It does not mutate a case, candidate run, analysis, or ledger,
 * and it never treats a ledger row as ticket authenticity or seller ownership proof.
 */
@Service
@RequiredArgsConstructor
public class Track1CandidateLinkService {
    private final MacroAuditEvidenceReadRepository clusters;
    private final Track1LedgerSeatLookupService ledgerSeats;
    private final CandidateRunApplicability applicability;

    public Track1CandidateLinkDto.View query(UUID tenantId, UUID eventId, List<CaseSourceReference> sources,
            Function<UUID, Optional<ListingCandidateMatchRun>> runs) {
        List<CaseSourceReference> graph = sources.stream().filter(source ->
                source.getSourceType() == CaseSourceReference.Type.STORED_CLUSTER_EVIDENCE).toList();
        List<CaseSourceReference> candidate = sources.stream().filter(source ->
                source.getSourceType() == CaseSourceReference.Type.LISTING_CANDIDATE_MATCH).toList();
        if (graph.isEmpty() || candidate.isEmpty())
            return new Track1CandidateLinkDto.View(Track1CandidateLinkDto.Status.NOT_APPLICABLE, List.of());
        List<Track1CandidateLinkDto.Item> items = graph.stream().flatMap(cluster -> candidate.stream()
                .map(run -> link(tenantId, eventId, cluster, run, runs))).toList();
        Track1CandidateLinkDto.Status overall = items.stream().anyMatch(item -> item.status()
                == Track1CandidateLinkDto.Status.LINKED) ? Track1CandidateLinkDto.Status.LINKED
                : items.stream().anyMatch(item -> item.status() == Track1CandidateLinkDto.Status.LEDGER_UNAVAILABLE)
                ? Track1CandidateLinkDto.Status.LEDGER_UNAVAILABLE
                : items.stream().anyMatch(item -> item.status() == Track1CandidateLinkDto.Status.NO_TRACK1_LINK)
                ? Track1CandidateLinkDto.Status.NO_TRACK1_LINK : Track1CandidateLinkDto.Status.NOT_APPLICABLE;
        return new Track1CandidateLinkDto.View(overall, items);
    }

    private Track1CandidateLinkDto.Item link(UUID tenantId, UUID eventId, CaseSourceReference graph,
            CaseSourceReference candidate, Function<UUID, Optional<ListingCandidateMatchRun>> runs) {
        UUID runId = candidate.getListingCandidateMatchRunId();
        ListingCandidateMatchRun run = runId == null ? null : runs.apply(runId).orElse(null);
        if (!usable(tenantId, eventId, candidate, run)) return item(graph, candidate, runId,
                Track1CandidateLinkDto.Status.NOT_APPLICABLE, List.of());
        try {
            List<MacroAuditEvidence> members = clusters.findMembers(tenantId, eventId,
                    graph.getAnalysisRequestId(), graph.getClusterId());
            if (members.isEmpty()) return item(graph, candidate, runId,
                    Track1CandidateLinkDto.Status.NOT_APPLICABLE, List.of());
            Track1LedgerSeatLookupService.Result lookup = ledgerSeats.lookup(tenantId, eventId,
                    run.getVerifiedPerformanceId(), run.getReservationSourceVersion(), run.getReservationBatchId(),
                    run.getCandidateId(), members);
            List<Track1CandidateLinkDto.LedgerSeat> seats = lookup.seats().stream().map(seat ->
                    new Track1CandidateLinkDto.LedgerSeat(seat.auditId(),
                            Track1CandidateLinkDto.LedgerSeatStatus.valueOf(seat.status().name()),
                            seat.zone(), seat.row(), seat.seat(), seat.seats().stream()
                                    .map(value -> new Track1CandidateLinkDto.Seat(value.zone(), value.row(), value.seat()))
                                    .toList())).toList();
            if (!lookup.ledgerAvailable()) return item(graph, candidate, runId,
                    Track1CandidateLinkDto.Status.LEDGER_UNAVAILABLE, seats);
            return item(graph, candidate, runId, lookup.targetReferenceFound()
                    ? Track1CandidateLinkDto.Status.LINKED : Track1CandidateLinkDto.Status.NO_TRACK1_LINK, seats);
        } catch (RuntimeException unavailable) {
            return unavailable(graph, candidate, runId, tenantId, eventId);
        }
    }

    private boolean usable(UUID tenantId, UUID eventId, CaseSourceReference source, ListingCandidateMatchRun run) {
        return run != null && run.getStatus() == CandidateMatchDecision.Status.MATCH_CANDIDATE
                && run.getCandidateId() != null && run.getCandidateCount() != null && run.getCandidateCount() == 1
                && run.getReservationBatchId() != null && run.getReservationSourceVersion() != null
                && run.getVerifiedPerformanceId() != null
                && tenantId.equals(run.getTenantId()) && eventId.equals(run.getEventId())
                && run.getId().equals(source.getListingCandidateMatchRunId())
                && run.getStatus().name().equals(source.getCandidateStatus())
                && run.getCandidateId().equals(source.getCandidateId())
                && applicability.assess(run) == com.ticket.defender_core.adapter.in.web.dto.CandidatePipelineDto.Applicability.CURRENT;
    }

    private Track1CandidateLinkDto.Item unavailable(CaseSourceReference graph, CaseSourceReference candidate,
            UUID runId, UUID tenantId, UUID eventId) {
        try {
            List<MacroAuditEvidence> members = clusters.findMembers(tenantId, eventId,
                    graph.getAnalysisRequestId(), graph.getClusterId());
            return item(graph, candidate, runId, Track1CandidateLinkDto.Status.LEDGER_UNAVAILABLE, members.stream()
                    .map(member -> new Track1CandidateLinkDto.LedgerSeat(member.getAuditId(),
                            Track1CandidateLinkDto.LedgerSeatStatus.LEDGER_UNAVAILABLE, null, null, null, List.of())).toList());
        } catch (RuntimeException ignored) {
            return item(graph, candidate, runId, Track1CandidateLinkDto.Status.LEDGER_UNAVAILABLE, List.of());
        }
    }

    private static Track1CandidateLinkDto.Item item(CaseSourceReference graph, CaseSourceReference candidate,
            UUID runId, Track1CandidateLinkDto.Status status, List<Track1CandidateLinkDto.LedgerSeat> seats) {
        return new Track1CandidateLinkDto.Item(graph.getId(), candidate.getId(), runId, status, seats,
                Track1CandidateLinkDto.LinkBasis.RESERVATION_REFERENCE);
    }
}
