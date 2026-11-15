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

/** BL-02 private-object and staging job. READY_TO_PUBLISH is explicitly not publication. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "td_bulk_import_jobs", uniqueConstraints = {
        @UniqueConstraint(name = "uk_td_bulk_import_idempotency", columnNames = {"created_by_membership_id", "idempotency_key_digest"}),
        @UniqueConstraint(name = "uk_td_bulk_import_source_batch", columnNames = {"tenant_id", "source_system", "source_batch_id"}),
        @UniqueConstraint(name = "uk_td_bulk_import_job_tenant", columnNames = {"id", "tenant_id"})
}, indexes = @Index(name = "idx_td_bulk_import_tenant_created", columnList = "tenant_id,created_at"))
public class BulkLedgerImportJob {
    public enum Mode { FULL_SNAPSHOT, CHANGE_FEED }
    public enum Format { CSV }
    public enum Status { CREATED, UPLOAD_PENDING, UPLOADED, VALIDATING, STAGING, READY_TO_PUBLISH, FAILED_RETRYABLE, REJECTED, CANCELLED }

    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36) private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "tenant_id", nullable = false, length = 36) private UUID tenantId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "created_by_membership_id", nullable = false, length = 36) private UUID createdByMembershipId;
    @Column(name = "idempotency_key_digest", nullable = false, length = 64) private String idempotencyKeyDigest;
    @Column(name = "request_digest", nullable = false, length = 64) private String requestDigest;
    @Column(name = "schema_version", nullable = false, length = 32) private String schemaVersion;
    @Column(name = "source_system", nullable = false, length = 64) private String sourceSystem;
    @Column(name = "source_batch_id", nullable = false, length = 128) private String sourceBatchId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private Mode mode;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private Format format;
    @Column(name = "declared_object_sha256", nullable = false, length = 64) private String declaredObjectSha256;
    @Column(name = "declared_file_bytes", nullable = false) private long declaredFileBytes;
    @Column(name = "declared_row_count", nullable = false) private long declaredRowCount;
    @Column(name = "upload_object_key_digest", length = 64) private String uploadObjectKeyDigest;
    @Column(name = "upload_object_version", length = 256) private String uploadObjectVersion;
    @Column(name = "upload_expires_at") private LocalDateTime uploadExpiresAt;
    @Column(name = "complete_snapshot", nullable = false) private boolean completeSnapshot;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private Status status;
    @Column(name = "processed_row_count", nullable = false) private long processedRowCount;
    @Column(name = "accepted_row_count", nullable = false) private long acceptedRowCount;
    @Column(name = "rejected_row_count", nullable = false) private long rejectedRowCount;
    @Column(name = "attempt_count", nullable = false) private int attemptCount;
    @Column(name = "lease_owner", length = 128) private String leaseOwner;
    @Column(name = "lease_until") private LocalDateTime leaseUntil;
    @Column(name = "fence_token") private long fenceToken;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
    @Column(name = "cancelled_at") private LocalDateTime cancelledAt;
    @Version @Column(name = "row_version", nullable = false) private long rowVersion;

    public BulkLedgerImportJob(UUID tenantId, UUID membershipId, String keyDigest, String requestDigest,
            String schemaVersion, String sourceSystem, String sourceBatchId, Mode mode, Format format,
            String sha256, long bytes, long rows, boolean completeSnapshot, LocalDateTime now) {
        this.id = UUID.randomUUID();
        this.tenantId = Objects.requireNonNull(tenantId); this.createdByMembershipId = Objects.requireNonNull(membershipId);
        this.idempotencyKeyDigest = required(keyDigest, 64); this.requestDigest = required(requestDigest, 64);
        this.schemaVersion = required(schemaVersion, 32); this.sourceSystem = required(sourceSystem, 64);
        this.sourceBatchId = required(sourceBatchId, 128); this.mode = Objects.requireNonNull(mode);
        this.format = Objects.requireNonNull(format); this.declaredObjectSha256 = required(sha256, 64);
        if (bytes < 0 || rows < 0) throw new IllegalArgumentException("negative declared count");
        this.declaredFileBytes = bytes; this.declaredRowCount = rows; this.completeSnapshot = completeSnapshot;
        this.status = Status.CREATED; this.createdAt = Objects.requireNonNull(now); this.updatedAt = now;
    }

    public void beginUpload(String keyDigest, LocalDateTime expiresAt, LocalDateTime now) {
        if (status != Status.CREATED && status != Status.FAILED_RETRYABLE) throw new IllegalStateException("job is not uploadable");
        this.uploadObjectKeyDigest = required(keyDigest, 64); this.uploadExpiresAt = Objects.requireNonNull(expiresAt);
        if (!expiresAt.isAfter(now)) throw new IllegalArgumentException("expired upload session");
        this.uploadObjectVersion = null; this.status = Status.UPLOAD_PENDING; this.updatedAt = now;
    }

    public void completeUpload(String versionId, LocalDateTime now) {
        if (status != Status.UPLOAD_PENDING || uploadExpiresAt == null || !uploadExpiresAt.isAfter(now))
            throw new IllegalStateException("upload session is not active");
        this.uploadObjectVersion = required(versionId, 256); this.status = Status.UPLOADED; this.updatedAt = now;
    }

    public void startValidation(String owner, LocalDateTime leaseUntil, LocalDateTime now) {
        boolean expiredLeaseRecovery = (status == Status.VALIDATING || status == Status.STAGING)
                && this.leaseUntil != null && this.leaseUntil.isBefore(now);
        if (status != Status.UPLOADED && status != Status.FAILED_RETRYABLE && !expiredLeaseRecovery)
            throw new IllegalStateException("job is not processable");
        if (leaseUntil == null || !leaseUntil.isAfter(now)) throw new IllegalArgumentException("invalid lease");
        this.status = Status.VALIDATING; this.leaseOwner = required(owner, 128); this.leaseUntil = leaseUntil;
        this.fenceToken++; this.attemptCount++; this.updatedAt = now;
    }

    public void beginStaging(long fence, LocalDateTime now) {
        requireFence(fence); if (status != Status.VALIDATING) throw new IllegalStateException("job is not validating");
        status = Status.STAGING; updatedAt = now;
    }

    public void readyToPublish(long fence, long processed, long accepted, long rejected, LocalDateTime now) {
        requireFence(fence); if (status != Status.STAGING || processed < 0 || accepted < 0 || rejected < 0 || processed != accepted + rejected)
            throw new IllegalStateException("invalid staging completion");
        processedRowCount = processed; acceptedRowCount = accepted; rejectedRowCount = rejected;
        leaseOwner = null; leaseUntil = null; status = Status.READY_TO_PUBLISH; updatedAt = now;
    }

    public void reject(long fence, long processed, long rejected, LocalDateTime now) {
        requireFence(fence); if (processed < 0 || rejected < 0 || rejected > processed) throw new IllegalArgumentException("invalid counts");
        processedRowCount = processed; acceptedRowCount = processed - rejected; rejectedRowCount = rejected;
        leaseOwner = null; leaseUntil = null; status = Status.REJECTED; updatedAt = now;
    }

    public void retryableFailure(long fence, LocalDateTime now) {
        requireFence(fence); leaseOwner = null; leaseUntil = null; status = Status.FAILED_RETRYABLE; updatedAt = now;
    }

    public void cancel(LocalDateTime now) {
        if (status == Status.READY_TO_PUBLISH || status == Status.REJECTED || status == Status.CANCELLED) throw new IllegalStateException("job is not cancellable");
        leaseOwner = null; leaseUntil = null;
        status = Status.CANCELLED; cancelledAt = Objects.requireNonNull(now); updatedAt = now;
    }

    private void requireFence(long fence) {
        if (fence != fenceToken || leaseOwner == null) throw new IllegalStateException("stale worker");
    }

    private static String required(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException("invalid bulk import field");
        return value;
    }
}
