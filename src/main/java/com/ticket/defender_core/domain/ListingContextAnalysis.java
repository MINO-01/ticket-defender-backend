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

/**
 * 비공개 저장된 팬 제보 이미지 1건에 대한 Track 2 Listing Context 분석 시도입니다.
 * 원본 이미지는 저장하지 않고, 분석 입력의 출처와 Python 응답의 관찰 결과만 기록합니다.
 * FOUND 관찰은 모델 추출 후보이며 사실 확인이나 부정 거래 판정이 아닙니다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "listing_context_analyses", uniqueConstraints = {
        @UniqueConstraint(name = "uk_listing_analyses_request", columnNames = "request_id"),
        @UniqueConstraint(name = "uk_listing_analyses_active_evidence", columnNames = "active_evidence_id"),
        @UniqueConstraint(name = "uk_listing_analyses_idempotency",
                columnNames = {"created_by_membership_id", "evidence_id", "idempotency_key_digest"})
}, indexes = @Index(name = "idx_listing_analyses_evidence_time", columnList = "evidence_id,created_at"))
public class ListingContextAnalysis {
    /** 실행 단계입니다. IN_PROGRESS만 활성 슬롯을 점유합니다. */
    public enum Execution { IN_PROGRESS, SUCCEEDED, INPUT_REJECTED, STORAGE_FAILED, ANALYZER_FAILED, ABANDONED }
    /** Python 계약의 전체 분석 상태입니다. 실행 실패는 항상 FAILED로 기록합니다. */
    public enum AnalysisStatus { COMPLETED, PARTIAL, UNVERIFIABLE, FAILED }

    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "id", length = 36, nullable = false)
    private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "tenant_id", length = 36, nullable = false)
    private UUID tenantId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "event_id", length = 36, nullable = false)
    private UUID eventId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "fan_report_id", length = 36, nullable = false)
    private UUID fanReportId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "evidence_id", length = 36, nullable = false)
    private UUID evidenceId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "created_by_membership_id", length = 36, nullable = false)
    private UUID createdByMembershipId;
    @Column(name = "idempotency_key_digest", length = 64, nullable = false)
    private String idempotencyKeyDigest;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "request_id", length = 36, nullable = false)
    private UUID requestId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "active_evidence_id", length = 36)
    private UUID activeEvidenceId;
    @Column(name = "object_key_sha256", length = 64, nullable = false)
    private String objectKeySha256;
    @Column(name = "object_marker", length = 160)
    private String objectMarker;
    @Column(name = "expected_sha256", length = 64, nullable = false)
    private String expectedSha256;
    @Column(name = "verified_sha256", length = 64)
    private String verifiedSha256;
    @Column(name = "expected_byte_size", nullable = false)
    private long expectedByteSize;
    @Column(name = "verified_byte_size")
    private Long verifiedByteSize;
    @Column(name = "media_type", length = 32, nullable = false)
    private String mediaType;
    @Enumerated(EnumType.STRING) @Column(name = "execution_status", length = 32, nullable = false)
    private Execution executionStatus;
    @Enumerated(EnumType.STRING) @Column(name = "analysis_status", length = 16)
    private AnalysisStatus analysisStatus;
    @Column(name = "error_code", length = 64)
    private String errorCode;
    @Column(name = "analyzer_http_status")
    private Integer analyzerHttpStatus;
    @Column(name = "analyzer_error_code", length = 64)
    private String analyzerErrorCode;
    @Column(name = "model_name", length = 128)
    private String modelName;
    @Column(name = "model_version", length = 128)
    private String modelVersion;
    @Column(name = "analyzed_at")
    private LocalDateTime analyzedAt;
    @Column(name = "review_required")
    private Boolean reviewRequired;
    @Column(name = "response_sha256", length = 64)
    private String responseSha256;
    @Column(name = "candidate_match_status", length = 32)
    private String candidateMatchStatus;
    @Column(name = "candidate_match_reason", length = 64)
    private String candidateMatchReason;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "lease_expires_at", nullable = false)
    private LocalDateTime leaseExpiresAt;
    @Column(name = "completed_at")
    private LocalDateTime completedAt;
    @Version @Column(name = "row_version", nullable = false)
    private long rowVersion;

    public ListingContextAnalysis(UUID id, UUID tenantId, UUID eventId, UUID fanReportId, UUID evidenceId,
            UUID createdByMembershipId, String idempotencyKeyDigest, UUID requestId, String objectKeySha256,
            String objectMarker, String expectedSha256, long expectedByteSize, String mediaType,
            LocalDateTime createdAt, LocalDateTime leaseExpiresAt) {
        this.id = Objects.requireNonNull(id);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.eventId = Objects.requireNonNull(eventId);
        this.fanReportId = Objects.requireNonNull(fanReportId);
        this.evidenceId = Objects.requireNonNull(evidenceId);
        this.createdByMembershipId = Objects.requireNonNull(createdByMembershipId);
        this.idempotencyKeyDigest = Objects.requireNonNull(idempotencyKeyDigest);
        this.requestId = Objects.requireNonNull(requestId);
        this.objectKeySha256 = Objects.requireNonNull(objectKeySha256);
        this.objectMarker = objectMarker;
        this.expectedSha256 = Objects.requireNonNull(expectedSha256);
        this.expectedByteSize = expectedByteSize;
        this.mediaType = Objects.requireNonNull(mediaType);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.leaseExpiresAt = Objects.requireNonNull(leaseExpiresAt);
        this.executionStatus = Execution.IN_PROGRESS;
        this.activeEvidenceId = evidenceId;
    }

    public boolean inProgress() {
        return executionStatus == Execution.IN_PROGRESS;
    }

    public boolean leaseExpired(LocalDateTime now) {
        return inProgress() && !leaseExpiresAt.isAfter(now);
    }

    public void recordVerifiedInput(String sha256, long byteSize) {
        requireInProgress();
        this.verifiedSha256 = Objects.requireNonNull(sha256);
        this.verifiedByteSize = byteSize;
    }

    public void succeed(AnalysisStatus status, String modelName, String modelVersion, LocalDateTime analyzedAt,
            String responseSha256, String candidateMatchReason, LocalDateTime now) {
        requireInProgress();
        if (status == null || status == AnalysisStatus.FAILED) throw new IllegalArgumentException("Invalid status");
        this.analysisStatus = status;
        this.modelName = Objects.requireNonNull(modelName);
        this.modelVersion = modelVersion;
        this.analyzedAt = Objects.requireNonNull(analyzedAt);
        this.reviewRequired = Boolean.TRUE;
        this.responseSha256 = Objects.requireNonNull(responseSha256);
        close(Execution.SUCCEEDED, candidateMatchReason, now);
    }

    public void fail(Execution execution, String errorCode, Integer analyzerHttpStatus, String analyzerErrorCode,
            LocalDateTime now) {
        requireInProgress();
        if (execution == null || execution == Execution.IN_PROGRESS || execution == Execution.SUCCEEDED)
            throw new IllegalArgumentException("Invalid failure stage");
        this.analysisStatus = AnalysisStatus.FAILED;
        this.errorCode = Objects.requireNonNull(errorCode);
        this.analyzerHttpStatus = analyzerHttpStatus;
        this.analyzerErrorCode = analyzerErrorCode;
        close(execution, "ANALYSIS_NOT_SUCCEEDED", now);
    }

    private void close(Execution execution, String candidateMatchReason, LocalDateTime now) {
        this.executionStatus = execution;
        this.activeEvidenceId = null;
        this.candidateMatchStatus = "NOT_LINKED";
        this.candidateMatchReason = Objects.requireNonNull(candidateMatchReason);
        this.completedAt = Objects.requireNonNull(now);
    }

    private void requireInProgress() {
        if (!inProgress()) throw new IllegalStateException("Analysis is already closed");
    }
}
