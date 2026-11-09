package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.Track1QuantityDto;
import com.ticket.defender_core.adapter.out.persistence.Track1PurchaseLedgerBatchRepository;
import com.ticket.defender_core.adapter.out.persistence.Track1PurchaseLedgerRowRepository;
import com.ticket.defender_core.adapter.out.persistence.Track1PurchaseLedgerErrorRepository;
import com.ticket.defender_core.adapter.out.persistence.VerifiedPerformanceRepository;
import com.ticket.defender_core.domain.Track1PurchaseLedgerBatch;
import com.ticket.defender_core.domain.Track1PurchaseLedgerError;
import com.ticket.defender_core.domain.Track1PurchaseLedgerRow;
import com.ticket.defender_core.domain.VerifiedPerformance;
import com.ticket.defender_core.security.AdminRole;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 합성 격리 환경의 구매 수량 원장을 원자적으로 반입합니다.
 * 원문 참조는 메모리에서 HMAC으로만 바꾸고, 없는 구매시각과 수량은 만들지 않고 거절합니다.
 */
@Service
@RequiredArgsConstructor
public class Track1QuantityLedgerService {
    static final Set<AdminRole> VIEW_ROLES = Set.of(AdminRole.TENANT_ADMIN, AdminRole.ANALYST, AdminRole.REVIEWER, AdminRole.APPROVER);
    static final int MAX_ROWS = 1_000;
    private static final Set<AdminRole> IMPORT_ROLES = Set.of(AdminRole.TENANT_ADMIN);
    private static final int MAX_REFERENCE = 128;

    private final CandidatePipelineAccess access;
    private final SyntheticReservationImportGate gate;
    private final VerifiedPerformanceRepository performances;
    private final Track1PurchaseLedgerBatchRepository batches;
    private final Track1PurchaseLedgerRowRepository rows;
    private final Track1PurchaseLedgerErrorRepository errors;

    @Transactional
    public Track1QuantityDto.LedgerBatch importLedger(UUID eventId, String idempotencyKey, Track1QuantityDto.ImportRequest input) {
        CandidatePipelineAccess.Scope scope = access.event(eventId, IMPORT_ROLES);
        if (input == null || input.performanceId() == null || input.sourceAsOf() == null) throw CandidatePipelineSupport.invalid();
        String keyDigest = CandidatePipelineSupport.idempotencyDigest(idempotencyKey);
        String sourceVersion = CandidatePipelineSupport.bounded(input.sourceVersion(), MAX_REFERENCE);
        Track1PurchaseLedgerBatch.InputType inputType = inputType(input.inputType());
        boolean snapshot = inputType == Track1PurchaseLedgerBatch.InputType.ACCOUNT_LIMIT_SNAPSHOT;
        if (!snapshot && (input.rows() == null || input.rows().isEmpty() || input.rows().size() > MAX_ROWS)) throw CandidatePipelineSupport.invalid();
        if (snapshot && input.rows() != null && input.rows().size() > MAX_ROWS) throw CandidatePipelineSupport.invalid();

        LocalDateTime now = now();
        UUID isolationId = gate.requireSyntheticScope(scope.tenantId(), now);
        Hashers hashers = hashers();
        String requestDigest = requestDigest(scope.tenantId(), sourceVersion, inputType, input, hashers);

        var replay = batches.findByCreatedByMembershipIdAndEventIdAndIdempotencyKeyDigest(scope.membershipId(), scope.eventId(), keyDigest);
        if (replay.isPresent()) {
            if (!replay.get().getRequestDigest().equals(requestDigest)) throw conflict("IDEMPOTENCY_KEY_REUSED");
            return view(replay.get());
        }

        VerifiedPerformance performance = performances.lockByIdAndScope(input.performanceId(), scope.tenantId(), scope.eventId())
                .orElseThrow(Track1QuantityLedgerService::notFound);
        if (performance.getStatus() != VerifiedPerformance.Status.ACTIVE) throw conflict("PERFORMANCE_WITHDRAWN");
        var sameSource = batches.findByTenantIdAndEventIdAndPerformanceIdAndAcceptedSourceVersion(
                scope.tenantId(), scope.eventId(), performance.getId(), sourceVersion);
        if (sameSource.isPresent()) {
            if (sameSource.get().getRequestDigest().equals(requestDigest)) return view(sameSource.get());
            throw conflict("LEDGER_SOURCE_VERSION_CONFLICT");
        }

        Validation validation = snapshot ? Validation.snapshotBlocked(input)
                : validate(scope.tenantId(), input, sourceVersion, hashers, now);
        boolean accepted = validation.errors().isEmpty();
        Track1PurchaseLedgerBatch.Status status = accepted ? Track1PurchaseLedgerBatch.Status.ACCEPTED : Track1PurchaseLedgerBatch.Status.REJECTED;
        int version = batches.maxLedgerVersion(scope.tenantId(), scope.eventId(), performance.getId()) + 1;
        Track1PurchaseLedgerBatch batch = new Track1PurchaseLedgerBatch(new Track1PurchaseLedgerBatch.Spec(
                scope.tenantId(), scope.eventId(), performance.getId(), scope.membershipId(), keyDigest, requestDigest,
                accepted ? rowsDigest(validation.rows()) : null, accepted ? track2LinkDigest(validation.rows()) : null,
                inputType, sourceVersion, utc(input.sourceAsOf()), version,
                blank(input.quantityBasis()) || input.quantityBasis().length() > MAX_REFERENCE ? null : input.quantityBasis(),
                hashers.account().keyVersion(), isolationId, Boolean.TRUE.equals(input.completeSnapshot()), status,
                validation.inputCount(), accepted ? validation.rows().size() : 0,
                accepted ? 0 : validation.errors().size(), now));
        try {
            batches.saveAndFlush(batch);
            if (accepted) {
                rows.saveAll(validation.rows().stream().map(row -> row.toEntity(batch.getId(), sourceVersion, batch.getSourceAsOf())).toList());
                rows.flush();
            } else {
                errors.saveAll(validation.errors().stream()
                        .map(error -> new Track1PurchaseLedgerError(batch.getId(), error.rowIndex(), error.code())).toList());
                errors.flush();
            }
        } catch (DataIntegrityViolationException collision) {
            // 동시 반입은 MySQL UNIQUE가 결정하며 이 트랜잭션은 전체가 롤백됩니다.
            throw conflict("LEDGER_IMPORT_CONCURRENT_UPDATE");
        }
        return view(batch);
    }

    @Transactional(readOnly = true)
    public Track1QuantityDto.Page<Track1QuantityDto.LedgerBatch> history(UUID eventId, int page, int pageSize) {
        validatePage(page, pageSize);
        CandidatePipelineAccess.Scope scope = access.event(eventId, VIEW_ROLES);
        var found = batches.findByTenantIdAndEventId(scope.tenantId(), scope.eventId(),
                PageRequest.of(page, pageSize, Sort.by("createdAt").descending().and(Sort.by("id").descending())));
        Map<UUID, List<Track1QuantityDto.ImportError>> byBatch = new HashMap<>();
        if (found.hasContent()) {
            errors.findByBatchIdIn(found.stream().map(Track1PurchaseLedgerBatch::getId).toList()).stream()
                    .sorted(Comparator.comparing(Track1PurchaseLedgerError::getRowIndex, Comparator.nullsFirst(Comparator.naturalOrder()))
                            .thenComparing(Track1PurchaseLedgerError::getCode))
                    .forEach(error -> byBatch.computeIfAbsent(error.getBatchId(), ignored -> new ArrayList<>())
                            .add(new Track1QuantityDto.ImportError(error.getRowIndex(), error.getCode())));
        }
        return new Track1QuantityDto.Page<>(found.stream().map(batch -> view(batch, byBatch.getOrDefault(batch.getId(), List.of()))).toList(),
                page, pageSize, found.hasNext());
    }

    static void validatePage(int page, int pageSize) {
        if (page < 0 || pageSize < 1 || pageSize > 100 || (long) page * pageSize > Integer.MAX_VALUE) throw CandidatePipelineSupport.invalid();
    }

    /** 저장된 행을 같은 규칙으로 다시 계산해 반입 후 변조나 누락을 확인할 때도 사용합니다. */
    static String rowsDigest(List<? extends DigestRow> digestRows) {
        List<String> fields = new ArrayList<>();
        fields.add("TRACK1_PURCHASE_LEDGER_ROWS");
        digestRows.stream().sorted(Comparator.comparingInt(DigestRow::index)).forEach(row -> fields.addAll(row.canonical()));
        return CandidatePipelineSupport.sha256(fields.toArray(String[]::new));
    }

    static String track2LinkDigest(List<? extends LinkDigestRow> rows) {
        List<String> fields = new ArrayList<>(List.of("TRACK1_PURCHASE_LEDGER_TRACK2_LINKS"));
        rows.stream().sorted(Comparator.comparingInt(LinkDigestRow::index)).forEach(row -> fields.add(row.track2Reservation()));
        return CandidatePipelineSupport.sha256(fields.toArray(String[]::new));
    }

    interface DigestRow {
        int index();
        List<String> canonical();
    }
    interface LinkDigestRow { int index(); String track2Reservation(); }

    static List<String> canonical(int index, String account, String reservation, String ticket, LocalDateTime purchasedAt, int quantity,
            Track1PurchaseLedgerRow.QuantityUnit unit, Track1PurchaseLedgerRow.TicketStatus status, Track1PurchaseLedgerRow.Signals signals) {
        return List.of(Integer.toString(index), account, reservation, ticket, purchasedAt.toString(), Integer.toString(quantity), unit.name(), status.name(),
                String.valueOf(signals.payment()), String.valueOf(signals.address()), String.valueOf(signals.device()), String.valueOf(signals.ip()));
    }

    private Validation validate(UUID tenantId, Track1QuantityDto.ImportRequest input, String sourceVersion, Hashers hashers, LocalDateTime now) {
        List<Row> valid = new ArrayList<>();
        List<RowError> invalid = new ArrayList<>();
        LocalDateTime sourceAsOf = utc(input.sourceAsOf());
        if (sourceAsOf.isAfter(now)) invalid.add(new RowError(null, "SOURCE_AS_OF_IN_FUTURE"));
        if (blank(input.quantityBasis()) || input.quantityBasis().length() > MAX_REFERENCE) invalid.add(new RowError(null, "QUANTITY_BASIS_REQUIRED"));
        Set<String> tickets = new HashSet<>();
        Map<String, String> reservationOwners = new HashMap<>();
        List<Track1QuantityDto.ImportRow> submitted = input.rows();
        for (int index = 0; index < submitted.size(); index++) {
            Track1QuantityDto.ImportRow row = submitted.get(index);
            String code = row == null ? "INVALID_ROW" : rowProblem(row, sourceAsOf);
            if (code != null) {
                invalid.add(new RowError(index, code));
                continue;
            }
            String account = hashers.account().hash(tenantId, row.accountReference());
            String reservation = hashers.reservation().hash(tenantId, row.reservationReference());
            String track2Reservation = hashers.track2Reservation().hash(tenantId, row.reservationReference());
            String ticket = hashers.ticket().hash(tenantId, row.ticketReference());
            if (!tickets.add(ticket)) {
                invalid.add(new RowError(index, "DUPLICATE_TICKET_REFERENCE"));
                continue;
            }
            if (!account.equals(reservationOwners.computeIfAbsent(reservation, ignored -> account))) {
                invalid.add(new RowError(index, "RESERVATION_ACCOUNT_CONFLICT"));
                continue;
            }
            valid.add(new Row(index, account, reservation, track2Reservation, ticket, utc(row.purchasedAt()), row.quantity(),
                    Track1PurchaseLedgerRow.QuantityUnit.valueOf(row.quantityUnit()),
                    Track1PurchaseLedgerRow.TicketStatus.valueOf(row.ticketStatus()),
                    new Track1PurchaseLedgerRow.Signals(signal(hashers.payment(), tenantId, row.paymentReference()),
                            signal(hashers.address(), tenantId, row.addressReference()),
                            signal(hashers.device(), tenantId, row.deviceReference()),
                            signal(hashers.ip(), tenantId, row.ipReference()))));
        }
        return new Validation(submitted.size(), valid, invalid);
    }

    private static String rowProblem(Track1QuantityDto.ImportRow row, LocalDateTime sourceAsOf) {
        if (badReference(row.accountReference()) || badReference(row.reservationReference()) || badReference(row.ticketReference())) return "INVALID_REFERENCE";
        if (row.purchasedAt() == null) return "PURCHASED_AT_REQUIRED";
        if (utc(row.purchasedAt()).isAfter(sourceAsOf)) return "PURCHASED_AFTER_SOURCE_AS_OF";
        if (row.quantity() == null || row.quantity() < 1) return "QUANTITY_REQUIRED";
        Track1PurchaseLedgerRow.QuantityUnit unit = enumValue(Track1PurchaseLedgerRow.QuantityUnit.class, row.quantityUnit());
        if (unit == null) return "QUANTITY_UNIT_REQUIRED";
        if (unit == Track1PurchaseLedgerRow.QuantityUnit.TICKET && row.quantity() != 1) return "TICKET_UNIT_QUANTITY_MUST_BE_ONE";
        if (enumValue(Track1PurchaseLedgerRow.TicketStatus.class, row.ticketStatus()) == null) return "TICKET_STATUS_REQUIRED";
        List<String> signals = new ArrayList<>();
        for (String value : new String[] {row.paymentReference(), row.addressReference(), row.deviceReference(), row.ipReference()}) {
            if (value != null) signals.add(value);
        }
        if (signals.isEmpty()) return "RELATIONSHIP_SIGNAL_REQUIRED";
        return signals.stream().anyMatch(Track1QuantityLedgerService::badReference) ? "INVALID_REFERENCE" : null;
    }

    private static boolean badReference(String value) { return blank(value) || value.length() > MAX_REFERENCE; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        if (value == null) return null;
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static String signal(TenantPurposeReferenceHasher hasher, UUID tenantId, String value) {
        return value == null ? null : hasher.hash(tenantId, value);
    }

    private static Track1PurchaseLedgerBatch.InputType inputType(String value) {
        Track1PurchaseLedgerBatch.InputType parsed = enumValue(Track1PurchaseLedgerBatch.InputType.class, value);
        if (parsed == null) throw CandidatePipelineSupport.invalid();
        return parsed;
    }

    private Hashers hashers() {
        return new Hashers(gate.referenceHasher("TRACK1_QTY_ACCOUNT"), gate.referenceHasher("TRACK1_QTY_RESERVATION"),
                gate.referenceHasher(TenantPurposeReferenceHasher.RESERVATION_REFERENCE_PURPOSE),
                gate.referenceHasher("TRACK1_QTY_TICKET"), gate.referenceHasher("TRACK1_QTY_PAYMENT"),
                gate.referenceHasher("TRACK1_QTY_ADDRESS"), gate.referenceHasher("TRACK1_QTY_DEVICE"), gate.referenceHasher("TRACK1_QTY_IP"));
    }

    /** 길이 접두 SHA-256에 HMAC 토큰만 넣어 같은 입력 판단에도 원문 참조를 보존하지 않습니다. */
    private static String requestDigest(UUID tenantId, String sourceVersion, Track1PurchaseLedgerBatch.InputType type,
            Track1QuantityDto.ImportRequest input, Hashers hashers) {
        List<String> fields = new ArrayList<>(List.of("TRACK1_LEDGER_IMPORT_REQUEST", input.performanceId().toString(), sourceVersion, type.name(),
                input.sourceAsOf().toInstant().toString(), String.valueOf(input.quantityBasis()), String.valueOf(input.completeSnapshot())));
        List<Track1QuantityDto.ImportRow> submitted = input.rows() == null ? List.of() : input.rows();
        fields.add(Integer.toString(submitted.size()));
        for (Track1QuantityDto.ImportRow row : submitted) {
            if (row == null) {
                fields.add("NULL");
                continue;
            }
            for (String reference : new String[] {row.accountReference(), row.reservationReference(), row.ticketReference(),
                    row.paymentReference(), row.addressReference(), row.deviceReference(), row.ipReference()}) {
                fields.add(blank(reference) ? "NULL:" + reference : hashers.account().hash(tenantId, CandidatePipelineSupport.sha256("TRACK1_LEDGER_REQUEST_REFERENCE", reference)));
            }
            fields.add(row.purchasedAt() == null ? "NULL" : row.purchasedAt().toInstant().toString());
            fields.add(String.valueOf(row.quantity()));
            fields.add(String.valueOf(row.quantityUnit()));
            fields.add(String.valueOf(row.ticketStatus()));
        }
        return hashers.account().hash(tenantId, CandidatePipelineSupport.sha256(fields.toArray(String[]::new)));
    }

    private Track1QuantityDto.LedgerBatch view(Track1PurchaseLedgerBatch batch) {
        List<Track1QuantityDto.ImportError> items = errors.findByBatchIdIn(List.of(batch.getId())).stream()
                .sorted(Comparator.comparing(Track1PurchaseLedgerError::getRowIndex, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(Track1PurchaseLedgerError::getCode))
                .map(error -> new Track1QuantityDto.ImportError(error.getRowIndex(), error.getCode())).toList();
        return view(batch, items);
    }

    private static Track1QuantityDto.LedgerBatch view(Track1PurchaseLedgerBatch batch, List<Track1QuantityDto.ImportError> items) {
        return new Track1QuantityDto.LedgerBatch(batch.getId(), batch.getEventId(), batch.getPerformanceId(), batch.getInputType().name(),
                batch.getSourceVersion(), batch.getSourceAsOf().atOffset(ZoneOffset.UTC), batch.getLedgerVersion(), batch.getQuantityBasis(),
                batch.isCompleteSnapshot(), batch.getStatus().name(), batch.getInputRowCount(), batch.getAcceptedRowCount(),
                batch.getRejectedRowCount(), items, batch.getCreatedAt().atOffset(ZoneOffset.UTC));
    }

    private static LocalDateTime utc(OffsetDateTime value) { return value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(); }
    private static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
    private static CaseApiException conflict(String code) { return new CaseApiException(HttpStatus.CONFLICT, code); }
    private static CaseApiException notFound() { return new CaseApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND"); }

    private record Hashers(TenantPurposeReferenceHasher account, TenantPurposeReferenceHasher reservation,
                           TenantPurposeReferenceHasher track2Reservation, TenantPurposeReferenceHasher ticket,
                           TenantPurposeReferenceHasher payment, TenantPurposeReferenceHasher address, TenantPurposeReferenceHasher device,
                           TenantPurposeReferenceHasher ip) { }

    private record RowError(Integer rowIndex, String code) { }

    private record Row(int index, String account, String reservation, String track2Reservation, String ticket, LocalDateTime purchasedAt, int quantity,
                       Track1PurchaseLedgerRow.QuantityUnit unit, Track1PurchaseLedgerRow.TicketStatus status,
                       Track1PurchaseLedgerRow.Signals signals) implements DigestRow, LinkDigestRow {
        @Override public List<String> canonical() {
            return Track1QuantityLedgerService.canonical(index, account, reservation, ticket, purchasedAt, quantity, unit, status, signals);
        }
        @Override public String track2Reservation() { return track2Reservation; }

        Track1PurchaseLedgerRow toEntity(UUID batchId, String sourceVersion, LocalDateTime sourceAsOf) {
            return new Track1PurchaseLedgerRow(batchId, index, account, reservation, track2Reservation, ticket, purchasedAt, quantity, unit, status, signals, sourceVersion, sourceAsOf);
        }
    }

    private record Validation(int inputCount, List<Row> rows, List<RowError> errors) {
        /** Data v2가 지원하지 않는 입력은 개별 티켓으로 변환하지 않고 실패 이력으로만 남깁니다. */
        static Validation snapshotBlocked(Track1QuantityDto.ImportRequest input) {
            return new Validation(input.rows() == null ? 0 : input.rows().size(), List.of(),
                    List.of(new RowError(null, "ACCOUNT_SNAPSHOT_NOT_SUPPORTED_BY_DATA_V2")));
        }
    }
}
