package com.ticket.defender_core.domain;

import com.ticket.defender_core.application.service.CandidateMatchDecision;
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

/** Listing 분석과 특정 회차/예매 스냅샷을 바인딩하는 불변 후보 대조 실행 이력입니다. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "listing_candidate_match_runs", uniqueConstraints = {
        @UniqueConstraint(name = "uk_listing_candidate_run_idempotency",
                columnNames = {"created_by_membership_id", "analysis_id", "idempotency_key_digest"}),
        @UniqueConstraint(name = "uk_listing_candidate_run_active_analysis", columnNames = "active_analysis_id"),
        @UniqueConstraint(name = "uk_listing_candidate_run_scope_id",
                columnNames = {"tenant_id", "event_id", "id"})
}, indexes = @Index(name = "idx_listing_candidate_runs_analysis_time", columnList = "analysis_id,created_at"))
public class ListingCandidateMatchRun {
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
    @Column(name = "fan_report_id", nullable = false, length = 36)
    private UUID fanReportId;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "evidence_id", nullable = false, length = 36)
    private UUID evidenceId;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "analysis_id", nullable = false, length = 36)
    private UUID analysisId;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "created_by_membership_id", nullable = false, length = 36)
    private UUID createdByMembershipId;
    @Column(name = "idempotency_key_digest", nullable = false, length = 64)
    private String idempotencyKeyDigest;
    @Column(name = "request_digest", nullable = false, length = 64)
    private String requestDigest;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "active_analysis_id", length = 36)
    private UUID activeAnalysisId;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "performance_link_id", length = 36)
    private UUID performanceLinkId;
    @Column(name = "performance_link_revision")
    private Integer performanceLinkRevision;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "verified_performance_id", length = 36)
    private UUID verifiedPerformanceId;
    @Column(name = "verified_performance_revision")
    private Integer verifiedPerformanceRevision;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "reservation_source_id", length = 36)
    private UUID reservationSourceId;
    @Column(name = "reservation_source_revision")
    private Integer reservationSourceRevision;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "reservation_batch_id", length = 36)
    private UUID reservationBatchId;
    @Column(name = "reservation_source_version", length = 128)
    private String reservationSourceVersion;
    @Column(name = "evidence_sha256", length = 64)
    private String evidenceSha256;
    @Column(name = "analysis_response_sha256", length = 64)
    private String analysisResponseSha256;
    @Column(name = "analysis_row_version")
    private Long analysisRowVersion;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private CandidateMatchDecision.Status status;
    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 64)
    private CandidateMatchDecision.Reason reason;
    @Column(name = "candidate_count")
    private Integer candidateCount;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "candidate_id", length = 36)
    private UUID candidateId;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "completed_at")
    private LocalDateTime completedAt;
    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    public ListingCandidateMatchRun(UUID tenantId, UUID eventId, UUID fanReportId, UUID evidenceId, UUID analysisId,
            UUID createdByMembershipId, String idempotencyKeyDigest, String requestDigest, Binding binding,
            LocalDateTime now) {
        this.id = UUID.randomUUID();
        this.tenantId = Objects.requireNonNull(tenantId);
        this.eventId = Objects.requireNonNull(eventId);
        this.fanReportId = Objects.requireNonNull(fanReportId);
        this.evidenceId = Objects.requireNonNull(evidenceId);
        this.analysisId = Objects.requireNonNull(analysisId);
        this.createdByMembershipId = Objects.requireNonNull(createdByMembershipId);
        this.idempotencyKeyDigest = Objects.requireNonNull(idempotencyKeyDigest);
        this.requestDigest = Objects.requireNonNull(requestDigest);
        apply(binding);
        this.activeAnalysisId = analysisId;
        this.status = CandidateMatchDecision.Status.IN_PROGRESS;
        this.reason = CandidateMatchDecision.Reason.LOOKUP_STARTED;
        this.createdAt = Objects.requireNonNull(now);
    }

    public static ListingCandidateMatchRun terminal(UUID tenantId, UUID eventId, UUID fanReportId, UUID evidenceId,
            UUID analysisId, UUID createdByMembershipId, String idempotencyKeyDigest, String requestDigest,
            Binding binding, CandidateMatchDecision.Result result, LocalDateTime now) {
        ListingCandidateMatchRun run = new ListingCandidateMatchRun(tenantId, eventId, fanReportId, evidenceId,
                analysisId, createdByMembershipId, idempotencyKeyDigest, requestDigest, binding, now);
        run.complete(result, null, null, now);
        return run;
    }

    public void complete(CandidateMatchDecision.Result result, Integer candidateCount, UUID candidateId,
            LocalDateTime now) {
        if (activeAnalysisId == null) throw new IllegalStateException("Candidate match run is already closed");
        this.activeAnalysisId = null;
        this.status = Objects.requireNonNull(result).status();
        this.reason = result.reason();
        this.candidateCount = candidateCount;
        this.candidateId = candidateId;
        this.completedAt = Objects.requireNonNull(now);
    }

    public boolean inProgress() {
        return activeAnalysisId != null;
    }

    private void apply(Binding binding) {
        if (binding == null) return;
        this.performanceLinkId = binding.performanceLinkId();
        this.performanceLinkRevision = binding.performanceLinkRevision();
        this.verifiedPerformanceId = binding.verifiedPerformanceId();
        this.verifiedPerformanceRevision = binding.verifiedPerformanceRevision();
        this.reservationSourceId = binding.reservationSourceId();
        this.reservationSourceRevision = binding.reservationSourceRevision();
        this.reservationBatchId = binding.reservationBatchId();
        this.reservationSourceVersion = binding.reservationSourceVersion();
        this.evidenceSha256 = binding.evidenceSha256();
        this.analysisResponseSha256 = binding.analysisResponseSha256();
        this.analysisRowVersion = binding.analysisRowVersion();
    }

    public record Binding(UUID performanceLinkId, Integer performanceLinkRevision, UUID verifiedPerformanceId,
                          Integer verifiedPerformanceRevision, UUID reservationSourceId,
                          Integer reservationSourceRevision, UUID reservationBatchId,
                          String reservationSourceVersion, String evidenceSha256,
                          String analysisResponseSha256, Long analysisRowVersion) { }
}
