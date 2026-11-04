package com.ticket.defender_core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/** 원본 예매번호 없이 합성 스냅샷의 수락/거절 결과만 보존하는 배치입니다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "reservation_import_batches", uniqueConstraints = {
        @UniqueConstraint(name = "uk_reservation_import_batch_idempotency",
                columnNames = {"created_by_membership_id", "event_id", "idempotency_key_digest"}),
        @UniqueConstraint(name = "uk_reservation_import_batch_source_version",
                columnNames = {"tenant_id", "event_id", "performance_id", "source_version"}),
        @UniqueConstraint(name = "uk_reservation_import_batch_scope_id",
                columnNames = {"tenant_id", "event_id", "id"})
}, indexes = @Index(name = "idx_reservation_import_batches_scope_time", columnList = "tenant_id,event_id,created_at"))
public class ReservationImportBatch {
    public enum Status { ACCEPTED, REJECTED }

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", nullable = false, length = 36)
    private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tenant_id", nullable = false, length = 36)
    private UUID tenantId;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "event_id", nullable = false, length = 36)
    private UUID eventId;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "performance_id", nullable = false, length = 36)
    private UUID performanceId;
    @Column(name = "performance_ref", nullable = false, length = 128)
    private String performanceRef;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "created_by_membership_id", nullable = false, length = 36)
    private UUID createdByMembershipId;
    @Column(name = "idempotency_key_digest", nullable = false, length = 64)
    private String idempotencyKeyDigest;
    @Column(name = "request_digest", nullable = false, length = 64)
    private String requestDigest;
    @Column(name = "source_version", nullable = false, length = 128)
    private String sourceVersion;
    @Column(name = "reference_hmac_key_version", nullable = false, length = 32)
    private String referenceHmacKeyVersion;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "synthetic_isolation_id", nullable = false, length = 36)
    private UUID syntheticIsolationId;
    @Column(name = "complete_snapshot", nullable = false)
    private boolean completeSnapshot;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;
    @Column(name = "input_row_count", nullable = false)
    private int inputRowCount;
    @Column(name = "accepted_row_count", nullable = false)
    private int acceptedRowCount;
    @Column(name = "rejected_row_count", nullable = false)
    private int rejectedRowCount;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "completed_at", nullable = false)
    private LocalDateTime completedAt;
    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    public ReservationImportBatch(UUID tenantId, UUID eventId, VerifiedPerformance performance,
            UUID createdByMembershipId, String idempotencyKeyDigest, String requestDigest, String sourceVersion,
            String referenceHmacKeyVersion, UUID syntheticIsolationId, boolean completeSnapshot, Status status,
            int inputRowCount, int acceptedRowCount, int rejectedRowCount, LocalDateTime now) {
        this.id = UUID.randomUUID();
        this.tenantId = Objects.requireNonNull(tenantId);
        this.eventId = Objects.requireNonNull(eventId);
        this.performanceId = Objects.requireNonNull(performance.getId());
        this.performanceRef = performance.getPerformanceRef();
        this.createdByMembershipId = Objects.requireNonNull(createdByMembershipId);
        this.idempotencyKeyDigest = Objects.requireNonNull(idempotencyKeyDigest);
        this.requestDigest = Objects.requireNonNull(requestDigest);
        this.sourceVersion = required(sourceVersion, 128);
        this.referenceHmacKeyVersion = required(referenceHmacKeyVersion, 32);
        this.syntheticIsolationId = Objects.requireNonNull(syntheticIsolationId);
        this.completeSnapshot = completeSnapshot;
        this.status = Objects.requireNonNull(status);
        this.inputRowCount = nonNegative(inputRowCount);
        this.acceptedRowCount = nonNegative(acceptedRowCount);
        this.rejectedRowCount = nonNegative(rejectedRowCount);
        this.createdAt = Objects.requireNonNull(now);
        this.completedAt = now;
    }

    private static int nonNegative(int value) {
        if (value < 0) throw new IllegalArgumentException("row count must be non-negative");
        return value;
    }

    private static String required(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException("invalid value");
        return value;
    }
}
