package com.ticket.defender_core.application.service;

import com.ticket.defender_core.domain.MacroAuditEvidence;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 현재 완전한 합성 원장에 대한 Track 1 구성원 읽기 전용 조회의 공용 진입점이다.
 * HMAC 참조는 DTO나 로그 값에 포함하지 않으며, 조회 실패는 트랜잭션 경계 바깥에서 LEDGER_UNAVAILABLE로 변환한다.
 */
@Service
@RequiredArgsConstructor
public class Track1LedgerSeatLookupService {
    public enum Status { FOUND, NOT_IN_LEDGER, LEDGER_UNAVAILABLE }

    public record Seat(String zone, String row, String seat) { }
    /** Legacy scalar fields are populated only for one seat; seats preserves every matching ticket seat. */
    public record LedgerSeat(Long auditId, Status status, String zone, String row, String seat, List<Seat> seats) {
        public LedgerSeat(Long auditId, Status status, String zone, String row, String seat) {
            this(auditId, status, zone, row, seat,
                    zone == null ? List.of() : List.of(new Seat(zone, row, seat)));
        }
    }
    public record Result(boolean ledgerAvailable, boolean targetReferenceFound, List<LedgerSeat> seats) { }

    private final Track1LedgerSnapshotReader snapshots;
    private final SyntheticReservationImportGate importGate;

    public Result lookup(UUID tenantId, UUID eventId, UUID performanceId, String sourceVersion,
            UUID expectedBatchId, UUID targetCandidateId, List<MacroAuditEvidence> members) {
        if (members == null || members.isEmpty()) return new Result(false, false, List.of());
        Optional<TenantPurposeReferenceHasher> hasher = importGate.referenceHasherForRead();
        if (hasher.isEmpty()) return unavailable(members);
        try {
            return snapshots.read(tenantId, eventId, performanceId, sourceVersion, expectedBatchId, targetCandidateId,
                    members, hasher.get());
        } catch (RuntimeException unavailable) {
            return unavailable(members);
        }
    }

    static Result unavailable(List<MacroAuditEvidence> members) {
        return new Result(false, false, members.stream().map(member -> new LedgerSeat(member.getAuditId(),
                Status.LEDGER_UNAVAILABLE, null, null, null, List.of())).toList());
    }
}
