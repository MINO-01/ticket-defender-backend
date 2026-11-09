package com.ticket.defender_core.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** v2 분석 시도 1건입니다. 의존 서비스 실패와 평가 불가, 조사 신호 0건을 서로 다른 상태로 보존합니다. */
@Entity @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "track1_purchase_limit_runs", uniqueConstraints = {
        @UniqueConstraint(name = "uk_t1_limit_run_idempotency", columnNames = {"created_by_membership_id", "event_id", "idempotency_key_digest"}),
        @UniqueConstraint(name = "uk_t1_limit_run_active_batch", columnNames = "active_ledger_batch_id"),
        @UniqueConstraint(name = "uk_t1_limit_run_request", columnNames = "analysis_request_id")
}, indexes = @Index(name = "idx_t1_limit_run_scope_time", columnList = "tenant_id,event_id,created_at"))
public class Track1PurchaseLimitRun {
    public enum Execution { IN_PROGRESS, SUCCEEDED, INPUT_REJECTED, ANALYZER_UNAVAILABLE, ANALYZER_FAILED, INTERRUPTED }
    private static final Set<Execution> RETRYABLE = Set.of(Execution.ANALYZER_UNAVAILABLE, Execution.INTERRUPTED);

    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(length = 36) private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "tenant_id", nullable = false, length = 36) private UUID tenantId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "event_id", nullable = false, length = 36) private UUID eventId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "performance_id", nullable = false, length = 36) private UUID performanceId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "ledger_batch_id", nullable = false, length = 36) private UUID ledgerBatchId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "active_ledger_batch_id", length = 36) private UUID activeLedgerBatchId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "created_by_membership_id", nullable = false, length = 36) private UUID createdByMembershipId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "idempotency_key_digest", nullable = false, length = 64) private String idempotencyKeyDigest;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "request_digest", nullable = false, length = 64) private String requestDigest;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "analysis_request_id", nullable = false, length = 36) private UUID analysisRequestId;
    @Column(name = "ledger_source_version", nullable = false, length = 128) private String ledgerSourceVersion;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "policy_id", length = 36) private UUID policyId;
    @Column(name = "policy_version", length = 128) private String policyVersion;
    @Enumerated(EnumType.STRING) @Column(name = "execution_status", nullable = false, length = 32) private Execution executionStatus;
    @Column(name = "evaluation_status", length = 64) private String evaluationStatus;
    @Column(name = "evaluation_reason", length = 64) private String evaluationReason;
    @Column(name = "error_code", length = 64) private String errorCode;
    @Column(name = "algorithm", length = 32) private String algorithm;
    @Column(name = "algorithm_version", length = 160) private String algorithmVersion;
    @Column(name = "cluster_count") private Integer clusterCount;
    @Column(name = "netted_excluded_row_count", nullable = false) private int nettedExcludedRowCount;
    @Column(name = "analyzed_at") private LocalDateTime analyzedAt;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "completed_at") private LocalDateTime completedAt;
    @Version @Column(name = "row_version", nullable = false) private long rowVersion;

    public Track1PurchaseLimitRun(UUID tenant, UUID event, UUID performance, UUID batch, UUID membership, String key,
            String digest, String source, UUID policyId, String policyVersion, String evaluationReason,
            int nettedExcludedRows, LocalDateTime now) {
        id = UUID.randomUUID();
        analysisRequestId = UUID.randomUUID();
        tenantId = Objects.requireNonNull(tenant);
        eventId = Objects.requireNonNull(event);
        performanceId = Objects.requireNonNull(performance);
        ledgerBatchId = Objects.requireNonNull(batch);
        activeLedgerBatchId = batch;
        createdByMembershipId = Objects.requireNonNull(membership);
        idempotencyKeyDigest = required(key, 64);
        requestDigest = required(digest, 64);
        ledgerSourceVersion = required(source, 128);
        this.policyId = policyId;
        this.policyVersion = policyVersion;
        this.evaluationReason = evaluationReason;
        if (nettedExcludedRows < 0) throw new IllegalArgumentException("invalid excluded row count");
        nettedExcludedRowCount = nettedExcludedRows;
        createdAt = Objects.requireNonNull(now);
        executionStatus = Execution.IN_PROGRESS;
    }

    public void succeed(String evaluation, String algorithm, String version, int clusters, LocalDateTime analyzed, LocalDateTime now) {
        requireProgress();
        if (clusters < 0) throw new IllegalArgumentException("invalid cluster count");
        evaluationStatus = required(evaluation, 64);
        this.algorithm = required(algorithm, 32);
        algorithmVersion = required(version, 160);
        clusterCount = clusters;
        analyzedAt = Objects.requireNonNull(analyzed);
        executionStatus = Execution.SUCCEEDED;
        activeLedgerBatchId = null;
        completedAt = Objects.requireNonNull(now);
    }

    public void fail(Execution status, String code, LocalDateTime now) {
        requireProgress();
        if (status == Execution.IN_PROGRESS || status == Execution.SUCCEEDED) throw new IllegalArgumentException("invalid execution");
        executionStatus = status;
        errorCode = required(code, 64);
        activeLedgerBatchId = null;
        completedAt = Objects.requireNonNull(now);
    }

    public boolean isOpen() { return executionStatus == Execution.IN_PROGRESS; }
    public boolean isRetryable() { return RETRYABLE.contains(executionStatus); }

    private void requireProgress() {
        if (executionStatus != Execution.IN_PROGRESS) throw new IllegalStateException("run closed");
    }

    private static String required(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException("invalid run value");
        return value;
    }
}
