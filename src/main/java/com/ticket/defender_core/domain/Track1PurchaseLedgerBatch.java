package com.ticket.defender_core.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/** Track 1 v2용 불변 구매 수량 원장 배치입니다. 거절된 배치는 실패 이력으로만 남고 sourceVersion을 점유하지 않습니다. */
@Entity @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "track1_purchase_ledger_batches", uniqueConstraints = {
        @UniqueConstraint(name = "uk_t1_ledger_batch_idempotency", columnNames = {"created_by_membership_id", "event_id", "idempotency_key_digest"}),
        @UniqueConstraint(name = "uk_t1_ledger_batch_source", columnNames = {"tenant_id", "event_id", "performance_id", "accepted_source_version"}),
        @UniqueConstraint(name = "uk_t1_ledger_batch_version", columnNames = {"tenant_id", "event_id", "performance_id", "ledger_version"})
}, indexes = @Index(name = "idx_t1_ledger_batch_history", columnList = "tenant_id,event_id,created_at"))
public class Track1PurchaseLedgerBatch {
    public enum Status { ACCEPTED, REJECTED }
    public enum InputType { INDIVIDUAL_TICKET, ACCOUNT_LIMIT_SNAPSHOT }

    /** 생성 입력을 한곳에 모아 같은 타입 인자가 뒤바뀌는 것을 막습니다. */
    public record Spec(UUID tenantId, UUID eventId, UUID performanceId, UUID membershipId, String keyDigest,
                       String requestDigest, String rowsDigest, String track2LinkDigest, InputType inputType, String sourceVersion,
                       LocalDateTime sourceAsOf, int ledgerVersion, String quantityBasis, String hmacVersion,
                       UUID isolationId, boolean completeSnapshot, Status status, int inputRows, int acceptedRows,
                       int rejectedRows, LocalDateTime now) { }

    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36) private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "tenant_id", nullable = false, length = 36) private UUID tenantId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "event_id", nullable = false, length = 36) private UUID eventId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "performance_id", nullable = false, length = 36) private UUID performanceId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "created_by_membership_id", nullable = false, length = 36) private UUID createdByMembershipId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "idempotency_key_digest", nullable = false, length = 64) private String idempotencyKeyDigest;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "request_digest", nullable = false, length = 64) private String requestDigest;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "rows_digest", length = 64) private String rowsDigest;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "track2_link_digest", length = 64) private String track2LinkDigest;
    @Enumerated(EnumType.STRING) @Column(name = "input_type", nullable = false, length = 32) private InputType inputType;
    @Column(name = "source_version", nullable = false, length = 128) private String sourceVersion;
    @Column(name = "accepted_source_version", length = 128) private String acceptedSourceVersion;
    @Column(name = "source_as_of", nullable = false) private LocalDateTime sourceAsOf;
    @Column(name = "ledger_version", nullable = false) private int ledgerVersion;
    @Column(name = "quantity_basis", length = 128) private String quantityBasis;
    @Column(name = "reference_hmac_key_version", nullable = false, length = 32) private String referenceHmacKeyVersion;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "synthetic_isolation_id", nullable = false, length = 36) private UUID syntheticIsolationId;
    @Column(name = "complete_snapshot", nullable = false) private boolean completeSnapshot;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private Status status;
    @Column(name = "input_row_count", nullable = false) private int inputRowCount;
    @Column(name = "accepted_row_count", nullable = false) private int acceptedRowCount;
    @Column(name = "rejected_row_count", nullable = false) private int rejectedRowCount;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Version @Column(name = "row_version", nullable = false) private long rowVersion;

    public Track1PurchaseLedgerBatch(Spec spec) {
        id = UUID.randomUUID();
        tenantId = Objects.requireNonNull(spec.tenantId());
        eventId = Objects.requireNonNull(spec.eventId());
        performanceId = Objects.requireNonNull(spec.performanceId());
        createdByMembershipId = Objects.requireNonNull(spec.membershipId());
        idempotencyKeyDigest = required(spec.keyDigest(), 64);
        requestDigest = required(spec.requestDigest(), 64);
        rowsDigest = spec.rowsDigest();
        // Use the incoming status.  The entity field has not been assigned at this point,
        // so consulting it would silently drop the accepted batch's correlation digest.
        track2LinkDigest = spec.status() == Status.ACCEPTED ? required(spec.track2LinkDigest(), 64) : null;
        inputType = Objects.requireNonNull(spec.inputType());
        sourceVersion = required(spec.sourceVersion(), 128);
        sourceAsOf = Objects.requireNonNull(spec.sourceAsOf());
        if (spec.ledgerVersion() < 1) throw new IllegalArgumentException("invalid ledger version");
        ledgerVersion = spec.ledgerVersion();
        quantityBasis = spec.quantityBasis();
        referenceHmacKeyVersion = required(spec.hmacVersion(), 32);
        syntheticIsolationId = Objects.requireNonNull(spec.isolationId());
        completeSnapshot = spec.completeSnapshot();
        status = Objects.requireNonNull(spec.status());
        acceptedSourceVersion = status == Status.ACCEPTED ? sourceVersion : null;
        if (spec.inputRows() < 0 || spec.acceptedRows() < 0 || spec.rejectedRows() < 0)
            throw new IllegalArgumentException("invalid row count");
        inputRowCount = spec.inputRows();
        acceptedRowCount = spec.acceptedRows();
        rejectedRowCount = spec.rejectedRows();
        createdAt = Objects.requireNonNull(spec.now());
    }

    private static String required(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException("invalid ledger value");
        return value;
    }
}
