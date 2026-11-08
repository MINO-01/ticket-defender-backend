package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.Track1CandidateLinkDto;
import com.ticket.defender_core.adapter.out.persistence.MacroAuditEvidenceReadRepository;
import com.ticket.defender_core.adapter.out.persistence.ReservationCandidateRepository;
import com.ticket.defender_core.adapter.out.persistence.ReservationImportBatchRepository;
import com.ticket.defender_core.domain.CaseSourceReference;
import com.ticket.defender_core.domain.ListingCandidateMatchRun;
import com.ticket.defender_core.domain.MacroAuditEvidence;
import com.ticket.defender_core.domain.ReservationCandidate;
import com.ticket.defender_core.domain.ReservationImportBatch;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
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
    private final ReservationCandidateRepository candidates;
    private final ReservationImportBatchRepository batches;
    private final SyntheticReservationImportGate importGate;
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
        Optional<TenantPurposeReferenceHasher> hasher = importGate.referenceHasherForRead();
        if (hasher.isEmpty()) return unavailable(graph, candidate, runId, tenantId, eventId);
        try {
            ReservationImportBatch batch = batches.findById(run.getReservationBatchId()).orElse(null);
            if (!usableBatch(tenantId, eventId, run, batch)
                    || !batch.getReferenceHmacKeyVersion().equals(hasher.get().keyVersion()))
                return unavailable(graph, candidate, runId, tenantId, eventId);
            List<MacroAuditEvidence> members = clusters.findMembers(tenantId, eventId,
                    graph.getAnalysisRequestId(), graph.getClusterId());
            if (members.isEmpty()) return item(graph, candidate, runId,
                    Track1CandidateLinkDto.Status.NOT_APPLICABLE, List.of());
            Map<String, ReservationCandidate> ledger = candidates.findByBatchIdAndReservationRefHmacIn(batch.getId(),
                            members.stream().map(member -> hasher.get().hash(tenantId, member.getReservationNo())).toList())
                    .stream().collect(java.util.stream.Collectors.toMap(ReservationCandidate::getReservationRefHmac,
                            Function.identity()));
            List<Track1CandidateLinkDto.LedgerSeat> seats = members.stream().map(member -> {
                ReservationCandidate found = ledger.get(hasher.get().hash(tenantId, member.getReservationNo()));
                return found == null ? new Track1CandidateLinkDto.LedgerSeat(member.getAuditId(),
                        Track1CandidateLinkDto.LedgerSeatStatus.NOT_IN_LEDGER, null, null, null)
                        : new Track1CandidateLinkDto.LedgerSeat(member.getAuditId(),
                        Track1CandidateLinkDto.LedgerSeatStatus.FOUND, found.getZone(), found.getRowNum(),
                        found.getSeatNum());
            }).toList();
            boolean linked = ledger.containsKey(candidates.findById(run.getCandidateId())
                    .filter(found -> batch.getId().equals(found.getBatchId()))
                    .map(ReservationCandidate::getReservationRefHmac).orElse(""));
            return item(graph, candidate, runId, linked ? Track1CandidateLinkDto.Status.LINKED
                    : Track1CandidateLinkDto.Status.NO_TRACK1_LINK, seats);
        } catch (DataAccessException databaseUnavailable) {
            return unavailable(graph, candidate, runId, tenantId, eventId);
        } catch (RuntimeException invalidLedgerState) {
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

    private static boolean usableBatch(UUID tenantId, UUID eventId, ListingCandidateMatchRun run,
            ReservationImportBatch batch) {
        return batch != null && batch.getStatus() == ReservationImportBatch.Status.ACCEPTED
                && batch.isCompleteSnapshot() && tenantId.equals(batch.getTenantId()) && eventId.equals(batch.getEventId())
                && run.getVerifiedPerformanceId().equals(batch.getPerformanceId())
                && run.getReservationBatchId().equals(batch.getId())
                && run.getReservationSourceVersion().equals(batch.getSourceVersion());
    }

    private Track1CandidateLinkDto.Item unavailable(CaseSourceReference graph, CaseSourceReference candidate,
            UUID runId, UUID tenantId, UUID eventId) {
        try {
            List<MacroAuditEvidence> members = clusters.findMembers(tenantId, eventId,
                    graph.getAnalysisRequestId(), graph.getClusterId());
            return item(graph, candidate, runId, Track1CandidateLinkDto.Status.LEDGER_UNAVAILABLE, members.stream()
                    .map(member -> new Track1CandidateLinkDto.LedgerSeat(member.getAuditId(),
                            Track1CandidateLinkDto.LedgerSeatStatus.LEDGER_UNAVAILABLE, null, null, null)).toList());
        } catch (DataAccessException ignored) {
            return item(graph, candidate, runId, Track1CandidateLinkDto.Status.LEDGER_UNAVAILABLE, List.of());
        }
    }

    private static Track1CandidateLinkDto.Item item(CaseSourceReference graph, CaseSourceReference candidate,
            UUID runId, Track1CandidateLinkDto.Status status, List<Track1CandidateLinkDto.LedgerSeat> seats) {
        return new Track1CandidateLinkDto.Item(graph.getId(), candidate.getId(), runId, status, seats);
    }
}
