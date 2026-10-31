package com.ticket.defender_core.domain;

import com.ticket.defender_core.application.service.OfficialPdfTemplateRegistry;
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
 * Mutable, retryable work record for one approved decision.  It is deliberately separate from
 * {@link OfficialPdfArtifact}: a failed or interrupted attempt is never an official artifact.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "official_pdf_issuances", uniqueConstraints = {
        @UniqueConstraint(name = "uk_official_pdf_issuance_decision", columnNames = "approval_decision_id"),
        @UniqueConstraint(name = "uk_official_pdf_issuance_scope_id", columnNames = {
                "tenant_id", "event_id", "case_id", "id"}),
        @UniqueConstraint(name = "uk_official_pdf_issuance_scope_decision", columnNames = {
                "tenant_id", "event_id", "case_id", "id", "approval_decision_id"})
}, indexes = @Index(name = "idx_official_pdf_issuance_scope_time",
        columnList = "tenant_id,event_id,case_id,requested_at"))
public class OfficialPdfIssuance {
    public enum Status { RESERVED, RENDERING, OBJECT_STORED, GENERATED, FAILED, STALE }

    @Id @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "tenant_id", length = 36, nullable = false, updatable = false)
    private UUID tenantId;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "event_id", length = 36, nullable = false, updatable = false)
    private UUID eventId;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "case_id", length = 36, nullable = false, updatable = false)
    private UUID caseId;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "approval_decision_id", length = 36, nullable = false, updatable = false)
    private UUID approvalDecisionId;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "approval_request_id", length = 36, nullable = false, updatable = false)
    private UUID approvalRequestId;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "review_receipt_id", length = 36, nullable = false, updatable = false)
    private UUID reviewReceiptId;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "report_version_id", length = 36, nullable = false, updatable = false)
    private UUID reportVersionId;
    @Column(name = "report_revision", nullable = false, updatable = false)
    private long reportRevision;
    @Column(name = "report_content_sha256", length = 64, nullable = false, updatable = false)
    private String reportContentSha256;
    @Column(name = "report_template_version", length = 64, nullable = false, updatable = false)
    private String reportTemplateVersion;
    @Column(name = "report_template_sha256", length = 64, nullable = false, updatable = false)
    private String reportTemplateSha256;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "evidence_snapshot_id", length = 36, nullable = false, updatable = false)
    private UUID evidenceSnapshotId;
    @Column(name = "evidence_revision", nullable = false, updatable = false)
    private long evidenceRevision;
    @Column(name = "manifest_sha256", length = 64, nullable = false, updatable = false)
    private String manifestSha256;
    @Column(name = "official_template_version", length = 64, nullable = false, updatable = false)
    private String officialTemplateVersion;
    @Column(name = "official_template_sha256", length = 64, nullable = false, updatable = false)
    private String officialTemplateSha256;
    /** Server-owned, deterministic recovery key. Never expose it through a web DTO. */
    @Column(name = "candidate_object_key", length = 512, nullable = false, updatable = false)
    private String candidateObjectKey;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 24, nullable = false)
    private Status status;
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;
    @Column(name = "candidate_object_version", length = 512)
    private String candidateObjectVersion;
    @Column(name = "candidate_pdf_sha256", length = 64)
    private String candidatePdfSha256;
    @Column(name = "candidate_pdf_size")
    private Long candidatePdfSize;
    @Column(name = "last_failure_code", length = 96)
    private String lastFailureCode;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "requested_by_membership_id", length = 36, nullable = false, updatable = false)
    private UUID requestedByMembershipId;
    @Column(name = "requested_at", nullable = false, updatable = false)
    private LocalDateTime requestedAt;
    @Column(name = "completed_at")
    private LocalDateTime completedAt;
    @Version
    @Column(name = "issuance_version", nullable = false)
    private Long version;

    public OfficialPdfIssuance(ApprovalDecision decision, UUID requestedByMembershipId,
            OfficialPdfTemplateRegistry.Template template, LocalDateTime now) {
        this.id = UUID.randomUUID();
        this.tenantId = decision.getTenantId();
        this.eventId = decision.getEventId();
        this.caseId = decision.getCaseId();
        this.approvalDecisionId = decision.getId();
        this.approvalRequestId = decision.getApprovalRequestId();
        this.reviewReceiptId = decision.getReviewReceiptId();
        this.reportVersionId = decision.getReportVersionId();
        this.reportRevision = decision.getReportRevision();
        this.reportContentSha256 = decision.getReportContentSha256();
        this.reportTemplateVersion = decision.getReportTemplateVersion();
        this.reportTemplateSha256 = decision.getReportTemplateSha256();
        this.evidenceSnapshotId = decision.getEvidenceSnapshotId();
        this.evidenceRevision = decision.getEvidenceRevision();
        this.manifestSha256 = decision.getManifestSha256();
        this.officialTemplateVersion = Objects.requireNonNull(template.version());
        this.officialTemplateSha256 = Objects.requireNonNull(template.sha256());
        this.candidateObjectKey = "official-pdf/" + tenantId + "/" + eventId + "/" + caseId + "/" + id + ".pdf";
        this.status = Status.RESERVED;
        this.requestedByMembershipId = Objects.requireNonNull(requestedByMembershipId);
        this.requestedAt = Objects.requireNonNull(now);
    }

    public void beginAttempt() {
        if (status == Status.GENERATED) throw new IllegalStateException("Official PDF is already generated");
        if (status == Status.OBJECT_STORED) return;
        this.status = Status.RENDERING;
        this.attemptCount = Math.addExact(attemptCount, 1);
        this.lastFailureCode = null;
    }

    /** Reuses a verified candidate object after a DB finalization interruption; it never uploads a second PDF. */
    public void beginRecovery() {
        if (status == Status.GENERATED || candidateObjectVersion == null || candidatePdfSha256 == null
                || candidatePdfSize == null) throw new IllegalStateException("Official PDF object cannot be recovered");
        this.status = Status.OBJECT_STORED;
        this.attemptCount = Math.addExact(attemptCount, 1);
        this.lastFailureCode = null;
    }

    public boolean hasCandidateObject() {
        return candidateObjectVersion != null && candidatePdfSha256 != null && candidatePdfSize != null;
    }

    public void recordObject(String objectVersion, String sha256, long size) {
        if (status != Status.RENDERING && status != Status.OBJECT_STORED)
            throw new IllegalStateException("Official PDF is not rendering");
        if (objectVersion == null || objectVersion.isBlank() || sha256 == null || sha256.length() != 64 || size < 1)
            throw new IllegalArgumentException("Official PDF object proof is invalid");
        this.candidateObjectVersion = objectVersion;
        this.candidatePdfSha256 = sha256;
        this.candidatePdfSize = size;
        this.status = Status.OBJECT_STORED;
        this.lastFailureCode = null;
    }

    public void generated(LocalDateTime now) {
        if (status != Status.OBJECT_STORED || candidateObjectVersion == null || candidatePdfSha256 == null
                || candidatePdfSize == null) throw new IllegalStateException("Official PDF object is not verified");
        this.status = Status.GENERATED;
        this.completedAt = Objects.requireNonNull(now);
        this.lastFailureCode = null;
    }

    /**
     * Repairs only the mutable work record when a previously committed immutable artifact is found.
     * The artifact remains the source of truth, so this path never renders or uploads another PDF.
     */
    public void generatedFromArtifact(LocalDateTime generatedAt) {
        if (candidateObjectVersion == null || candidatePdfSha256 == null || candidatePdfSize == null) {
            throw new IllegalStateException("Official PDF object is not verified");
        }
        this.status = Status.GENERATED;
        this.completedAt = Objects.requireNonNull(generatedAt);
        this.lastFailureCode = null;
    }

    public void failed(String code) {
        if (status == Status.GENERATED) throw new IllegalStateException("Generated official PDF cannot fail");
        this.status = Status.FAILED;
        this.lastFailureCode = requiredCode(code);
    }

    public void stale(String code) {
        if (status == Status.GENERATED) throw new IllegalStateException("Generated official PDF cannot become stale");
        this.status = Status.STALE;
        this.lastFailureCode = requiredCode(code);
    }

    private static String requiredCode(String code) {
        if (code == null || code.isBlank() || code.length() > 96) throw new IllegalArgumentException("Failure code is invalid");
        return code;
    }
}
