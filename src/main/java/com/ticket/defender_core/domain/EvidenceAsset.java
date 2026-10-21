package com.ticket.defender_core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "evidence_assets", uniqueConstraints =
        @UniqueConstraint(name = "uk_evidence_assets_object_key", columnNames = "object_key"),
        indexes = @Index(name = "idx_evidence_assets_report", columnList = "fan_report_id"))
public class EvidenceAsset {
    public enum StorageStatus { PENDING, STORED, FAILED }

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", length = 36, nullable = false)
    private UUID evidenceId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "fan_report_id", length = 36, nullable = false)
    private UUID fanReportId;

    @Column(name = "object_key", length = 256, nullable = false)
    private String objectKey;

    @Column(name = "media_type", length = 32, nullable = false)
    private String mediaType;

    @Column(name = "byte_size", nullable = false)
    private long byteSize;

    @Column(name = "sha256", length = 64, nullable = false)
    private String sha256;

    @Column(name = "object_version", length = 160)
    private String objectVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "storage_status", length = 16, nullable = false)
    private StorageStatus storageStatus;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "retention_until", nullable = false)
    private LocalDateTime retentionUntil;

    public EvidenceAsset(UUID evidenceId, UUID fanReportId, String objectKey, String mediaType,
                         long byteSize, String sha256, LocalDateTime createdAt, LocalDateTime retentionUntil) {
        this.evidenceId = Objects.requireNonNull(evidenceId);
        this.fanReportId = Objects.requireNonNull(fanReportId);
        this.objectKey = Objects.requireNonNull(objectKey);
        this.mediaType = Objects.requireNonNull(mediaType);
        this.byteSize = byteSize;
        this.sha256 = Objects.requireNonNull(sha256);
        this.storageStatus = StorageStatus.PENDING;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.retentionUntil = Objects.requireNonNull(retentionUntil);
    }

    public void markStored(String version) {
        if (storageStatus != StorageStatus.PENDING) throw new IllegalStateException("Asset is not pending");
        objectVersion = version;
        storageStatus = StorageStatus.STORED;
    }

    public void markFailed() {
        storageStatus = StorageStatus.FAILED;
    }
}
