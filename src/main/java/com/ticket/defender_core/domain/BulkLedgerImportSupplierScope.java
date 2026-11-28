package com.ticket.defender_core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/** Supplier metadata remains unbound to product rows until the complete uploaded CSV has passed validation. */
@Entity
@Table(name = "td_bulk_import_supplier_scopes", uniqueConstraints = @UniqueConstraint(
        name = "uk_td_bulk_supplier_scope_code", columnNames = {"job_id", "source_event_code", "source_performance_code"}),
        indexes = @Index(name = "idx_td_bulk_supplier_scope_tenant", columnList = "tenant_id,job_id"))
public class BulkLedgerImportSupplierScope {
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36, nullable = false) private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "job_id", length = 36, nullable = false) private UUID jobId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "tenant_id", length = 36, nullable = false) private UUID tenantId;
    @Column(name = "source_event_code", length = 128, nullable = false) private String sourceEventCode;
    @Column(name = "source_performance_code", length = 128, nullable = false) private String sourcePerformanceCode;
    @Column(name = "event_title", length = 255, nullable = false) private String eventTitle;
    @Column(name = "performance_starts_at", nullable = false) private LocalDateTime performanceStartsAt;
    @Column(name = "per_account_limit") private Integer perAccountLimit;
    @Column(name = "scope_as_of") private LocalDateTime scopeAsOf;
    @Column(name = "declared_row_count", nullable = false) private long declaredRowCount;
    @Column(name = "cursor_start", length = 256) private String cursorStart;
    @Column(name = "cursor_end", length = 256) private String cursorEnd;
    @Column(name = "source_sequence_start") private Long sourceSequenceStart;
    @Column(name = "source_sequence_end") private Long sourceSequenceEnd;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "event_id", length = 36) private UUID eventId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "performance_id", length = 36) private UUID performanceId;
    @Column(name = "event_created", nullable = false) private boolean eventCreated;
    @Column(name = "performance_created", nullable = false) private boolean performanceCreated;

    protected BulkLedgerImportSupplierScope() { }

    public BulkLedgerImportSupplierScope(UUID jobId, UUID tenantId, String sourceEventCode,
            String sourcePerformanceCode, String eventTitle, LocalDateTime performanceStartsAt,
            Integer perAccountLimit, LocalDateTime scopeAsOf, long declaredRowCount, String cursorStart,
            String cursorEnd, Long sourceSequenceStart, Long sourceSequenceEnd) {
        id = UUID.randomUUID(); this.jobId = Objects.requireNonNull(jobId); this.tenantId = Objects.requireNonNull(tenantId);
        this.sourceEventCode = Objects.requireNonNull(sourceEventCode); this.sourcePerformanceCode = Objects.requireNonNull(sourcePerformanceCode);
        this.eventTitle = Objects.requireNonNull(eventTitle); this.performanceStartsAt = Objects.requireNonNull(performanceStartsAt);
        this.perAccountLimit = perAccountLimit; this.scopeAsOf = scopeAsOf; this.declaredRowCount = declaredRowCount;
        this.cursorStart = cursorStart; this.cursorEnd = cursorEnd; this.sourceSequenceStart = sourceSequenceStart;
        this.sourceSequenceEnd = sourceSequenceEnd;
        if (declaredRowCount < 0) throw new IllegalArgumentException("negative declared row count");
    }

    public void resolve(UUID eventId, UUID performanceId, boolean eventCreated, boolean performanceCreated) {
        boolean firstResolution = this.eventId == null;
        if (!firstResolution && (!this.eventId.equals(eventId) || !this.performanceId.equals(performanceId)))
            throw new IllegalStateException("supplier scope resolution cannot change after it is set");
        this.eventId = Objects.requireNonNull(eventId); this.performanceId = Objects.requireNonNull(performanceId);
        if (firstResolution) {
            this.eventCreated = eventCreated;
            this.performanceCreated = performanceCreated;
        }
    }

    public UUID getJobId() { return jobId; }
    public UUID getTenantId() { return tenantId; }
    public String getSourceEventCode() { return sourceEventCode; }
    public String getSourcePerformanceCode() { return sourcePerformanceCode; }
    public String getEventTitle() { return eventTitle; }
    public LocalDateTime getPerformanceStartsAt() { return performanceStartsAt; }
    public Integer getPerAccountLimit() { return perAccountLimit; }
    public LocalDateTime getScopeAsOf() { return scopeAsOf; }
    public long getDeclaredRowCount() { return declaredRowCount; }
    public String getCursorStart() { return cursorStart; }
    public String getCursorEnd() { return cursorEnd; }
    public Long getSourceSequenceStart() { return sourceSequenceStart; }
    public Long getSourceSequenceEnd() { return sourceSequenceEnd; }
    public UUID getEventId() { return eventId; }
    public UUID getPerformanceId() { return performanceId; }
    public boolean isEventCreated() { return eventCreated; }
    public boolean isPerformanceCreated() { return performanceCreated; }
}
