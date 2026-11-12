package com.ticket.defender_core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "td_bulk_import_scopes", uniqueConstraints = @UniqueConstraint(name = "uk_td_bulk_import_scope", columnNames = {"job_id", "event_id", "performance_id"}),
        indexes = @Index(name = "idx_td_bulk_import_scope_performance", columnList = "tenant_id,event_id,performance_id"))
public class BulkLedgerImportScope {
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36) private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "job_id", nullable = false, length = 36) private UUID jobId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "tenant_id", nullable = false, length = 36) private UUID tenantId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "event_id", nullable = false, length = 36) private UUID eventId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "performance_id", nullable = false, length = 36) private UUID performanceId;
    @Column(name = "scope_as_of") private LocalDateTime scopeAsOf;
    @Column(name = "declared_row_count", nullable = false) private long declaredRowCount;
    @Column(name = "cursor_start", length = 256) private String cursorStart;
    @Column(name = "cursor_end", length = 256) private String cursorEnd;

    public BulkLedgerImportScope(UUID jobId, UUID tenantId, UUID eventId, UUID performanceId, LocalDateTime scopeAsOf,
            long declaredRowCount, String cursorStart, String cursorEnd) {
        id = UUID.randomUUID(); this.jobId = Objects.requireNonNull(jobId); this.tenantId = Objects.requireNonNull(tenantId);
        this.eventId = Objects.requireNonNull(eventId); this.performanceId = Objects.requireNonNull(performanceId);
        this.scopeAsOf = scopeAsOf; if (declaredRowCount < 0) throw new IllegalArgumentException("negative row count");
        this.declaredRowCount = declaredRowCount; this.cursorStart = cursorStart; this.cursorEnd = cursorEnd;
    }
}
