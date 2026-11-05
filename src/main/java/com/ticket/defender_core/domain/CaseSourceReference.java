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
import java.util.UUID;

/** Server-verified source identity; original evidence remains in its owning store. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "case_source_references", indexes = {
        @Index(name = "idx_case_sources_scope_key", columnList = "tenant_id,event_id,source_key_digest")
}, uniqueConstraints = @UniqueConstraint(name = "uk_case_sources_case_key",
        columnNames = {"case_id", "source_key_digest"}))
public class CaseSourceReference {
    public enum Type { STORED_CLUSTER_EVIDENCE, FAN_REPORT, EVIDENCE_ASSET, LISTING_CANDIDATE_MATCH }

    @Id @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "id", length = 36, nullable = false)
    private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "case_id", length = 36, nullable = false)
    private UUID caseId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "tenant_id", length = 36, nullable = false)
    private UUID tenantId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "event_id", length = 36, nullable = false)
    private UUID eventId;
    @Enumerated(EnumType.STRING) @Column(name = "source_type", length = 32, nullable = false)
    private Type sourceType;
    @Column(name = "source_key_digest", length = 64, nullable = false)
    private String sourceKeyDigest;
    @Column(name = "analysis_request_id", length = 64)
    private String analysisRequestId;
    @Column(name = "cluster_id", length = 191)
    private String clusterId;
    @Column(name = "anchor_audit_id")
    private Long anchorAuditId;
    @Column(name = "analysis_algorithm", length = 80)
    private String analysisAlgorithm;
    @Column(name = "analysis_algorithm_version", length = 80)
    private String analysisAlgorithmVersion;
    @Column(name = "analysis_completed_at")
    private LocalDateTime analysisCompletedAt;
    @Column(name = "risk_score")
    private Double riskScore;
    @Column(name = "stored_member_count")
    private Integer storedMemberCount;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "fan_report_id", length = 36)
    private UUID fanReportId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "evidence_id", length = 36)
    private UUID evidenceId;
    @Column(name = "object_marker", length = 160)
    private String objectMarker;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "listing_candidate_match_run_id", length = 36)
    private UUID listingCandidateMatchRunId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "candidate_analysis_id", length = 36)
    private UUID candidateAnalysisId;
    @Column(name = "candidate_analysis_row_version")
    private Long candidateAnalysisRowVersion;
    @Column(name = "candidate_analysis_response_sha256", length = 64)
    private String candidateAnalysisResponseSha256;
    @Column(name = "candidate_evidence_sha256", length = 64)
    private String candidateEvidenceSha256;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "candidate_performance_link_id", length = 36)
    private UUID candidatePerformanceLinkId;
    @Column(name = "candidate_performance_link_revision")
    private Integer candidatePerformanceLinkRevision;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "candidate_verified_performance_id", length = 36)
    private UUID candidateVerifiedPerformanceId;
    @Column(name = "candidate_verified_performance_revision")
    private Integer candidateVerifiedPerformanceRevision;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "candidate_reservation_source_id", length = 36)
    private UUID candidateReservationSourceId;
    @Column(name = "candidate_reservation_source_revision")
    private Integer candidateReservationSourceRevision;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "candidate_reservation_batch_id", length = 36)
    private UUID candidateReservationBatchId;
    @Column(name = "candidate_reservation_source_version", length = 128)
    private String candidateReservationSourceVersion;
    @Column(name = "candidate_status", length = 32)
    private String candidateStatus;
    @Column(name = "candidate_reason", length = 64)
    private String candidateReason;
    @Column(name = "candidate_count")
    private Integer candidateCount;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "candidate_id", length = 36)
    private UUID candidateId;
    @Column(name = "linked_at", nullable = false)
    private LocalDateTime linkedAt;

    private CaseSourceReference(UUID caseId, UUID tenantId, UUID eventId, Type type,
            String digest, LocalDateTime now) {
        this.id = UUID.randomUUID();
        this.caseId = caseId;
        this.tenantId = tenantId;
        this.eventId = eventId;
        this.sourceType = type;
        this.sourceKeyDigest = digest;
        this.linkedAt = now;
    }

    public static CaseSourceReference cluster(Case target, String digest,
            MacroAuditEvidence anchor, int count, LocalDateTime now) {
        CaseSourceReference ref = new CaseSourceReference(target.getId(), target.getTenantId(),
                target.getEventId(), Type.STORED_CLUSTER_EVIDENCE, digest, now);
        ref.analysisRequestId = anchor.getAnalysisRequestId();
        ref.clusterId = anchor.getClusterId();
        ref.anchorAuditId = anchor.getAuditId();
        ref.analysisAlgorithm = anchor.getAnalysisAlgorithm();
        ref.analysisAlgorithmVersion = anchor.getAnalysisAlgorithmVersion();
        ref.analysisCompletedAt = anchor.getAnalysisCompletedAt();
        ref.riskScore = anchor.getRiskScore();
        ref.storedMemberCount = count;
        return ref;
    }

    public static CaseSourceReference report(Case target, String digest, UUID reportId, LocalDateTime now) {
        CaseSourceReference ref = new CaseSourceReference(target.getId(), target.getTenantId(),
                target.getEventId(), Type.FAN_REPORT, digest, now);
        ref.fanReportId = reportId;
        return ref;
    }

    public static CaseSourceReference asset(Case target, String digest, UUID reportId,
            UUID evidenceId, String marker, LocalDateTime now) {
        CaseSourceReference ref = new CaseSourceReference(target.getId(), target.getTenantId(),
                target.getEventId(), Type.EVIDENCE_ASSET, digest, now);
        ref.fanReportId = reportId;
        ref.evidenceId = evidenceId;
        ref.objectMarker = marker;
        return ref;
    }

    public static CaseSourceReference candidate(Case target, String digest, ListingCandidateMatchRun run,
            LocalDateTime now) {
        CaseSourceReference ref = new CaseSourceReference(target.getId(), target.getTenantId(),
                target.getEventId(), Type.LISTING_CANDIDATE_MATCH, digest, now);
        ref.fanReportId = run.getFanReportId();
        ref.evidenceId = run.getEvidenceId();
        ref.listingCandidateMatchRunId = run.getId();
        ref.candidateAnalysisId = run.getAnalysisId();
        ref.candidateAnalysisRowVersion = run.getAnalysisRowVersion();
        ref.candidateAnalysisResponseSha256 = run.getAnalysisResponseSha256();
        ref.candidateEvidenceSha256 = run.getEvidenceSha256();
        ref.candidatePerformanceLinkId = run.getPerformanceLinkId();
        ref.candidatePerformanceLinkRevision = run.getPerformanceLinkRevision();
        ref.candidateVerifiedPerformanceId = run.getVerifiedPerformanceId();
        ref.candidateVerifiedPerformanceRevision = run.getVerifiedPerformanceRevision();
        ref.candidateReservationSourceId = run.getReservationSourceId();
        ref.candidateReservationSourceRevision = run.getReservationSourceRevision();
        ref.candidateReservationBatchId = run.getReservationBatchId();
        ref.candidateReservationSourceVersion = run.getReservationSourceVersion();
        ref.candidateStatus = run.getStatus().name();
        ref.candidateReason = run.getReason().name();
        ref.candidateCount = run.getCandidateCount();
        ref.candidateId = run.getCandidateId();
        return ref;
    }
}
