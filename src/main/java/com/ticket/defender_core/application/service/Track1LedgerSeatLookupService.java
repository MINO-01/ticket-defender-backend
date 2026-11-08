package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.persistence.ReservationCandidateRepository;
import com.ticket.defender_core.adapter.out.persistence.ReservationDataSourceRepository;
import com.ticket.defender_core.adapter.out.persistence.ReservationImportBatchRepository;
import com.ticket.defender_core.domain.MacroAuditEvidence;
import com.ticket.defender_core.domain.ReservationCandidate;
import com.ticket.defender_core.domain.ReservationDataSource;
import com.ticket.defender_core.domain.ReservationImportBatch;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Shared, read-only Track 1 member lookup against the current complete synthetic ledger.
 * The HMAC references stay inside this service and are never part of a DTO or log value.
 */
@Service
@RequiredArgsConstructor
public class Track1LedgerSeatLookupService {
    public enum Status { FOUND, NOT_IN_LEDGER, LEDGER_UNAVAILABLE }

    public record LedgerSeat(Long auditId, Status status, String zone, String row, String seat) { }
    public record Result(boolean ledgerAvailable, boolean targetReferenceFound, List<LedgerSeat> seats) { }

    private final ReservationCandidateRepository candidates;
    private final ReservationImportBatchRepository batches;
    private final ReservationDataSourceRepository sources;
    private final SyntheticReservationImportGate importGate;

    public Result lookup(UUID tenantId, UUID eventId, UUID performanceId, String sourceVersion,
            UUID expectedBatchId, String targetReferenceHmac, List<MacroAuditEvidence> members) {
        if (members == null || members.isEmpty()) return new Result(false, false, List.of());
        Optional<TenantPurposeReferenceHasher> hasher = importGate.referenceHasherForRead();
        if (hasher.isEmpty()) return unavailable(members);
        try {
            ReservationDataSource source = sources.findByTenantIdAndEventIdAndPerformanceId(tenantId, eventId,
                    performanceId).orElse(null);
            ReservationImportBatch batch = source == null ? null : batches.findById(source.getCurrentBatchId()).orElse(null);
            if (!currentCompleteLedger(tenantId, eventId, performanceId, sourceVersion, expectedBatchId, source, batch)
                    || !batch.getReferenceHmacKeyVersion().equals(hasher.get().keyVersion())) return unavailable(members);
            Map<String, ReservationCandidate> ledger = candidates.findByBatchIdAndReservationRefHmacIn(batch.getId(),
                            members.stream().map(member -> hasher.get().hash(tenantId, member.getReservationNo())).toList())
                    .stream().collect(java.util.stream.Collectors.toMap(ReservationCandidate::getReservationRefHmac,
                            Function.identity()));
            List<LedgerSeat> seats = members.stream().map(member -> {
                ReservationCandidate found = ledger.get(hasher.get().hash(tenantId, member.getReservationNo()));
                return found == null ? new LedgerSeat(member.getAuditId(), Status.NOT_IN_LEDGER, null, null, null)
                        : new LedgerSeat(member.getAuditId(), Status.FOUND, found.getZone(), found.getRowNum(),
                        found.getSeatNum());
            }).toList();
            return new Result(true, targetReferenceHmac != null && ledger.containsKey(targetReferenceHmac), seats);
        } catch (RuntimeException unavailable) {
            return unavailable(members);
        }
    }

    private static boolean currentCompleteLedger(UUID tenantId, UUID eventId, UUID performanceId, String sourceVersion,
            UUID expectedBatchId, ReservationDataSource source, ReservationImportBatch batch) {
        return source != null && batch != null && source.isCompleteSnapshot()
                && tenantId.equals(batch.getTenantId()) && eventId.equals(batch.getEventId())
                && performanceId.equals(batch.getPerformanceId()) && batch.getStatus() == ReservationImportBatch.Status.ACCEPTED
                && batch.isCompleteSnapshot() && source.getCurrentBatchId().equals(batch.getId())
                && sourceVersion != null && sourceVersion.equals(source.getCurrentSourceVersion())
                && sourceVersion.equals(batch.getSourceVersion())
                && (expectedBatchId == null || expectedBatchId.equals(batch.getId()));
    }

    private static Result unavailable(List<MacroAuditEvidence> members) {
        return new Result(false, false, members.stream().map(member -> new LedgerSeat(member.getAuditId(),
                Status.LEDGER_UNAVAILABLE, null, null, null)).toList());
    }
}
