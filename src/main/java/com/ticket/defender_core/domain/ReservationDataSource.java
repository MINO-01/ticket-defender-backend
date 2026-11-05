package com.ticket.defender_core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/** 회차별 현재 완전 예매 스냅샷 포인터입니다. 과거 batch 행은 삭제하지 않습니다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "reservation_data_sources", uniqueConstraints = {
        @UniqueConstraint(name = "uk_reservation_data_source_scope",
                columnNames = {"tenant_id", "event_id", "performance_id"}),
        @UniqueConstraint(name = "uk_reservation_data_source_scope_id",
                columnNames = {"tenant_id", "event_id", "id"})
}, indexes = @Index(name = "idx_reservation_data_source_current", columnList = "current_batch_id"))
public class ReservationDataSource {
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
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "current_batch_id", nullable = false, length = 36)
    private UUID currentBatchId;
    @Column(name = "current_source_version", nullable = false, length = 128)
    private String currentSourceVersion;
    @Column(name = "complete_snapshot", nullable = false)
    private boolean completeSnapshot;
    @Column(name = "source_revision", nullable = false)
    private int sourceRevision;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    public ReservationDataSource(UUID tenantId, UUID eventId, UUID performanceId, ReservationImportBatch batch,
            LocalDateTime now) {
        this.id = UUID.randomUUID();
        this.tenantId = Objects.requireNonNull(tenantId);
        this.eventId = Objects.requireNonNull(eventId);
        this.performanceId = Objects.requireNonNull(performanceId);
        apply(batch, now);
        this.sourceRevision = 1;
    }

    public void activate(ReservationImportBatch batch, LocalDateTime now) {
        apply(batch, now);
        this.sourceRevision++;
    }

    private void apply(ReservationImportBatch batch, LocalDateTime now) {
        if (batch.getStatus() != ReservationImportBatch.Status.ACCEPTED || !batch.isCompleteSnapshot())
            throw new IllegalArgumentException("Only complete accepted batches can be current");
        this.currentBatchId = batch.getId();
        this.currentSourceVersion = batch.getSourceVersion();
        this.completeSnapshot = true;
        this.updatedAt = Objects.requireNonNull(now);
    }
}
