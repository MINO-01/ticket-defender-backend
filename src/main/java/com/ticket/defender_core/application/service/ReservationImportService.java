package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.CandidatePipelineDto;
import com.ticket.defender_core.adapter.out.persistence.ReservationCandidateRepository;
import com.ticket.defender_core.adapter.out.persistence.ReservationDataSourceRepository;
import com.ticket.defender_core.adapter.out.persistence.ReservationImportBatchRepository;
import com.ticket.defender_core.adapter.out.persistence.ReservationImportErrorRepository;
import com.ticket.defender_core.adapter.out.persistence.VerifiedPerformanceRepository;
import com.ticket.defender_core.domain.ReservationCandidate;
import com.ticket.defender_core.domain.ReservationDataSource;
import com.ticket.defender_core.domain.ReservationImportBatch;
import com.ticket.defender_core.domain.ReservationImportError;
import com.ticket.defender_core.domain.VerifiedPerformance;
import com.ticket.defender_core.security.AdminRole;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 합성 격리 환경에서만 원자적으로 완전 예매 스냅샷을 교체합니다. */
@Service
@RequiredArgsConstructor
public class ReservationImportService {
    private static final Set<AdminRole> IMPORT_ROLES = Set.of(AdminRole.TENANT_ADMIN);
    private static final int MAX_ROWS = 1_000;

    private final CandidatePipelineAccess access;
    private final SyntheticReservationImportGate gate;
    private final VerifiedPerformanceRepository performances;
    private final ReservationImportBatchRepository batches;
    private final ReservationImportErrorRepository errors;
    private final ReservationCandidateRepository candidates;
    private final ReservationDataSourceRepository sources;

    @Transactional
    public CandidatePipelineDto.ImportResult importSnapshot(UUID eventId, String idempotencyKey,
            CandidatePipelineDto.ImportReservations input) {
        CandidatePipelineAccess.Scope scope = access.event(eventId, IMPORT_ROLES);
        if (input == null || input.performanceId() == null) throw CandidatePipelineSupport.invalid();
        String keyDigest = CandidatePipelineSupport.idempotencyDigest(idempotencyKey);
        String sourceVersion = CandidatePipelineSupport.bounded(input.sourceVersion(), 128);
        LocalDateTime now = now();
        UUID isolationId = gate.requireSyntheticScope(scope.tenantId(), now);
        TenantPurposeReferenceHasher hasher = gate.referenceHasher();
        String requestDigest = requestDigest(scope.tenantId(), sourceVersion, input, hasher);

        var replay = batches.findByCreatedByMembershipIdAndEventIdAndIdempotencyKeyDigest(
                scope.membershipId(), eventId, keyDigest);
        if (replay.isPresent()) {
            if (!replay.get().getRequestDigest().equals(requestDigest)) throw conflict("IDEMPOTENCY_KEY_REUSED");
            return view(replay.get());
        }

        VerifiedPerformance performance = performances.lockByIdAndScope(input.performanceId(), scope.tenantId(), eventId)
                .orElseThrow(ReservationImportService::notFound);
        if (performance.getStatus() != VerifiedPerformance.Status.ACTIVE) throw conflict("PERFORMANCE_WITHDRAWN");
        var existingVersion = batches.findByTenantIdAndEventIdAndPerformanceIdAndSourceVersion(
                scope.tenantId(), eventId, performance.getId(), sourceVersion);
        if (existingVersion.isPresent()) {
            if (existingVersion.get().getRequestDigest().equals(requestDigest)) return view(existingVersion.get());
            throw conflict("RESERVATION_SOURCE_VERSION_CONFLICT");
        }

        Validation validation = validate(scope.tenantId(), input, hasher);
        ReservationImportBatch.Status status = validation.errors().isEmpty()
                ? ReservationImportBatch.Status.ACCEPTED : ReservationImportBatch.Status.REJECTED;
        ReservationImportBatch batch = new ReservationImportBatch(scope.tenantId(), eventId, performance,
                scope.membershipId(), keyDigest, requestDigest, sourceVersion, hasher.keyVersion(), isolationId,
                Boolean.TRUE.equals(input.completeSnapshot()), status, validation.inputCount(),
                status == ReservationImportBatch.Status.ACCEPTED ? validation.rows().size() : 0,
                status == ReservationImportBatch.Status.ACCEPTED ? 0 : validation.errors().size(), now);
        try {
            batches.saveAndFlush(batch);
            if (status == ReservationImportBatch.Status.REJECTED) {
                errors.saveAll(validation.errors().stream()
                        .map(error -> new ReservationImportError(batch.getId(), error.rowIndex(), error.code())).toList());
                errors.flush();
                return view(batch);
            }

            candidates.saveAll(validation.rows().stream().map(row -> new ReservationCandidate(batch.getId(), row.index(),
                    row.referenceHmac(), hasher.keyVersion(), row.zone(), row.row(), row.seat())).toList());
            candidates.flush();
            ReservationDataSource source = sources.lockByScope(scope.tenantId(), eventId, performance.getId()).orElse(null);
            if (source == null) {
                source = new ReservationDataSource(scope.tenantId(), eventId, performance.getId(), batch, now);
            } else {
                source.activate(batch, now);
            }
            sources.saveAndFlush(source);
            return view(batch);
        } catch (DataIntegrityViolationException collision) {
            // MySQL's unique keys settle concurrent idempotency/source creation; this transaction rolls back as one unit.
            throw conflict("RESERVATION_IMPORT_CONCURRENT_UPDATE");
        }
    }

    private Validation validate(UUID tenantId, CandidatePipelineDto.ImportReservations input,
            TenantPurposeReferenceHasher hasher) {
        List<CandidatePipelineDto.ReservationRow> submitted = input.rows();
        if (submitted == null || submitted.isEmpty() || submitted.size() > MAX_ROWS) throw CandidatePipelineSupport.invalid();
        List<Row> valid = new ArrayList<>();
        List<RowError> invalid = new ArrayList<>();
        if (!Boolean.TRUE.equals(input.completeSnapshot())) invalid.add(new RowError(0, "INCOMPLETE_SOURCE"));
        Set<String> references = new HashSet<>();
        for (int index = 0; index < submitted.size(); index++) {
            CandidatePipelineDto.ReservationRow row = submitted.get(index);
            if (row == null) {
                invalid.add(new RowError(index, "INVALID_ROW"));
                continue;
            }
            try {
                String reference = CandidatePipelineSupport.bounded(row.reservationReference(), 128);
                String hmac = hasher.hash(tenantId, reference);
                if (!references.add(hmac)) {
                    invalid.add(new RowError(index, "DUPLICATE_RESERVATION_REFERENCE"));
                    continue;
                }
                valid.add(new Row(index, hmac, CandidatePipelineSupport.seat(row.zone()),
                        CandidatePipelineSupport.seat(row.row()), CandidatePipelineSupport.seat(row.seat())));
            } catch (CaseApiException invalidRow) {
                invalid.add(new RowError(index, "INVALID_RESERVATION_ROW"));
            } catch (IllegalArgumentException invalidReference) {
                invalid.add(new RowError(index, "INVALID_RESERVATION_ROW"));
            }
        }
        return new Validation(submitted.size(), valid, invalid);
    }

    /**
     * 원문 예매번호는 메모리에서 tenant-specific HMAC token으로만 바꾼다. 이후 type token과 함께
     * length-prefixed SHA-256에 넣으므로 구분자, null, blank, 실제 문자열이 같은 요청으로 합쳐지지 않는다.
     */
    private String requestDigest(UUID tenantId, String sourceVersion, CandidatePipelineDto.ImportReservations input,
            TenantPurposeReferenceHasher hasher) {
        List<String> fields = new ArrayList<>();
        fields.add("RESERVATION_IMPORT_REQUEST");
        appendString(fields, "performanceId", input.performanceId().toString());
        appendString(fields, "sourceVersion", sourceVersion);
        appendBoolean(fields, "completeSnapshot", input.completeSnapshot());
        List<CandidatePipelineDto.ReservationRow> rows = input.rows();
        fields.add("rows");
        if (rows == null) {
            fields.add("NULL");
        } else {
            fields.add("PRESENT");
            fields.add(Integer.toString(rows.size()));
            for (CandidatePipelineDto.ReservationRow row : rows) {
                fields.add("row");
                if (row == null) {
                    fields.add("NULL");
                    continue;
                }
                fields.add("PRESENT");
                appendReferenceToken(fields, tenantId, hasher, row.reservationReference());
                appendString(fields, "zone", row.zone());
                appendString(fields, "row", row.row());
                appendString(fields, "seat", row.seat());
            }
        }
        return hasher.hash(tenantId, CandidatePipelineSupport.sha256(fields.toArray(String[]::new)));
    }

    private static void appendReferenceToken(List<String> fields, UUID tenantId, TenantPurposeReferenceHasher hasher,
            String reference) {
        fields.add("reservationReference");
        if (reference == null) {
            fields.add("NULL");
            return;
        }
        fields.add("STRING_HMAC");
        fields.add(hasher.hash(tenantId,
                CandidatePipelineSupport.sha256("RESERVATION_IMPORT_REQUEST_REFERENCE", reference)));
    }

    private static void appendString(List<String> fields, String name, String value) {
        fields.add(name);
        if (value == null) {
            fields.add("NULL");
        } else {
            fields.add("STRING");
            fields.add(value);
        }
    }

    private static void appendBoolean(List<String> fields, String name, Boolean value) {
        fields.add(name);
        if (value == null) {
            fields.add("NULL");
        } else {
            fields.add("BOOLEAN");
            fields.add(Boolean.toString(value));
        }
    }

    private CandidatePipelineDto.ImportResult view(ReservationImportBatch batch) {
        List<CandidatePipelineDto.ImportError> itemErrors = errors.findByBatchIdOrderByRowIndexAscCodeAsc(batch.getId())
                .stream().map(item -> new CandidatePipelineDto.ImportError(item.getRowIndex(), item.getCode())).toList();
        return new CandidatePipelineDto.ImportResult(batch.getId(), batch.getEventId(), batch.getPerformanceId(),
                batch.getSourceVersion(), batch.getStatus(), batch.isCompleteSnapshot(), batch.getInputRowCount(),
                batch.getAcceptedRowCount(), batch.getRejectedRowCount(), itemErrors, batch.getCompletedAt());
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static CaseApiException conflict(String code) {
        return new CaseApiException(HttpStatus.CONFLICT, code);
    }

    private static CaseApiException notFound() {
        return new CaseApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }

    private record Row(int index, String referenceHmac, String zone, String row, String seat) { }
    private record RowError(int rowIndex, String code) { }
    private record Validation(int inputCount, List<Row> rows, List<RowError> errors) { }
}
