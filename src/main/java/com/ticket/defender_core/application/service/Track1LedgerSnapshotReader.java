package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.persistence.ReservationCandidateRepository;
import com.ticket.defender_core.adapter.out.persistence.ReservationDataSourceRepository;
import com.ticket.defender_core.adapter.out.persistence.ReservationImportBatchRepository;
import com.ticket.defender_core.domain.MacroAuditEvidence;
import com.ticket.defender_core.domain.ReservationCandidate;
import com.ticket.defender_core.domain.ReservationDataSource;
import com.ticket.defender_core.domain.ReservationImportBatch;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * 원장 테이블을 호출자와 분리된 짧은 읽기 트랜잭션의 단일 스냅샷으로 조회한다.
 * 자기 호출로 트랜잭션이 누락되지 않도록 별도 Bean으로 두며, 실패 처리는 호출자가 경계 바깥에서 수행한다.
 */
@Component
@RequiredArgsConstructor
public class Track1LedgerSnapshotReader {
    private final ReservationCandidateRepository candidates;
    private final ReservationImportBatchRepository batches;
    private final ReservationDataSourceRepository sources;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Track1LedgerSeatLookupService.Result read(UUID tenantId, UUID eventId, UUID performanceId,
            String sourceVersion, UUID expectedBatchId, UUID targetCandidateId, List<MacroAuditEvidence> members,
            TenantPurposeReferenceHasher hasher) {
        ReservationDataSource source = sources.findByTenantIdAndEventIdAndPerformanceId(tenantId, eventId,
                performanceId).orElse(null);
        ReservationImportBatch batch = source == null ? null : batches.findById(source.getCurrentBatchId()).orElse(null);
        if (!currentCompleteLedger(tenantId, eventId, performanceId, sourceVersion, expectedBatchId, source, batch)
                || !batch.getReferenceHmacKeyVersion().equals(hasher.keyVersion()))
            return Track1LedgerSeatLookupService.unavailable(members);
        String targetReference = null;
        if (targetCandidateId != null) {
            // 후보를 확인할 수 없으면 미일치 판정의 근거가 없으므로 NO_TRACK1_LINK로 내려가지 않게 한다.
            targetReference = candidates.findById(targetCandidateId)
                    .filter(found -> batch.getId().equals(found.getBatchId()))
                    .map(ReservationCandidate::getReservationRefHmac).orElse(null);
            if (targetReference == null) return Track1LedgerSeatLookupService.unavailable(members);
        }
        Map<String, ReservationCandidate> ledger = candidates.findByBatchIdAndReservationRefHmacIn(batch.getId(),
                        members.stream().map(member -> hasher.hash(tenantId, member.getReservationNo())).toList())
                .stream().collect(java.util.stream.Collectors.toMap(ReservationCandidate::getReservationRefHmac,
                        Function.identity()));
        List<Track1LedgerSeatLookupService.LedgerSeat> seats = members.stream().map(member -> {
            ReservationCandidate found = ledger.get(hasher.hash(tenantId, member.getReservationNo()));
            return found == null
                    ? new Track1LedgerSeatLookupService.LedgerSeat(member.getAuditId(),
                            Track1LedgerSeatLookupService.Status.NOT_IN_LEDGER, null, null, null)
                    : new Track1LedgerSeatLookupService.LedgerSeat(member.getAuditId(),
                            Track1LedgerSeatLookupService.Status.FOUND, found.getZone(), found.getRowNum(),
                            found.getSeatNum());
        }).toList();
        return new Track1LedgerSeatLookupService.Result(true,
                targetReference != null && ledger.containsKey(targetReference), seats);
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
}
