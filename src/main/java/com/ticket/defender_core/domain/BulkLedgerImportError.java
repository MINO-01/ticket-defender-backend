package com.ticket.defender_core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/** Codes only: BL-01 must not store a raw row, account, reservation, IP, payment, or secret. */
@Entity @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "td_bulk_import_errors", indexes = @Index(name = "idx_td_bulk_import_error_job", columnList = "job_id,sequence_no"))
public class BulkLedgerImportError {
    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36) private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "job_id", nullable = false, length = 36) private UUID jobId;
    @Column(name = "sequence_no", nullable = false) private long sequenceNo;
    @Column(nullable = false, length = 64) private String code;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;

    public BulkLedgerImportError(UUID jobId, long sequenceNo, String code, LocalDateTime createdAt) {
        this.id = UUID.randomUUID(); this.jobId = jobId; this.sequenceNo = sequenceNo;
        this.code = code; this.createdAt = createdAt;
    }
}
