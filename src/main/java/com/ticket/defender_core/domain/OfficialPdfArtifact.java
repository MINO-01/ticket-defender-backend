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
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/** Immutable, downloadable official PDF only created after final eligibility and object verification. */
@Entity
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "official_pdf_artifacts", uniqueConstraints = {
        @UniqueConstraint(name = "uk_official_pdf_artifact_issuance", columnNames = "issuance_id"),
        @UniqueConstraint(name = "uk_official_pdf_artifact_decision", columnNames = "approval_decision_id"),
        @UniqueConstraint(name = "uk_official_pdf_artifact_scope_id", columnNames = {
                "tenant_id", "event_id", "case_id", "id"})
}, indexes = @Index(name = "idx_official_pdf_artifact_scope_time",
        columnList = "tenant_id,event_id,case_id,generated_at"))
public class OfficialPdfArtifact {
    @Id @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "tenant_id", length = 36, nullable = false, updatable = false)
    private UUID tenantId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "event_id", length = 36, nullable = false, updatable = false)
    private UUID eventId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "case_id", length = 36, nullable = false, updatable = false)
    private UUID caseId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "issuance_id", length = 36, nullable = false, updatable = false)
    private UUID issuanceId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "approval_decision_id", length = 36, nullable = false, updatable = false)
    private UUID approvalDecisionId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "approval_request_id", length = 36, nullable = false, updatable = false)
    private UUID approvalRequestId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "review_receipt_id", length = 36, nullable = false, updatable = false)
    private UUID reviewReceiptId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "report_version_id", length = 36, nullable = false, updatable = false)
    private UUID reportVersionId;
    @Column(name = "report_revision", nullable = false, updatable = false)
    private long reportRevision;
    @Column(name = "report_content_sha256", length = 64, nullable = false, updatable = false)
    private String reportContentSha256;
    @Column(name = "report_template_version", length = 64, nullable = false, updatable = false)
    private String reportTemplateVersion;
    @Column(name = "report_template_sha256", length = 64, nullable = false, updatable = false)
    private String reportTemplateSha256;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "evidence_snapshot_id", length = 36, nullable = false, updatable = false)
    private UUID evidenceSnapshotId;
    @Column(name = "evidence_revision", nullable = false, updatable = false)
    private long evidenceRevision;
    @Column(name = "manifest_sha256", length = 64, nullable = false, updatable = false)
    private String manifestSha256;
    @Column(name = "official_template_version", length = 64, nullable = false, updatable = false)
    private String officialTemplateVersion;
    @Column(name = "official_template_sha256", length = 64, nullable = false, updatable = false)
    private String officialTemplateSha256;
    @Column(name = "object_key", length = 512, nullable = false, updatable = false)
    private String objectKey;
    @Column(name = "object_version", length = 512, nullable = false, updatable = false)
    private String objectVersion;
    @Column(name = "pdf_sha256", length = 64, nullable = false, updatable = false)
    private String pdfSha256;
    @Column(name = "pdf_size", nullable = false, updatable = false)
    private long pdfSize;
    @Column(name = "generated_at", nullable = false, updatable = false)
    private LocalDateTime generatedAt;

    public OfficialPdfArtifact(OfficialPdfIssuance issuance, LocalDateTime generatedAt) {
        this.id = UUID.randomUUID();
        this.tenantId = issuance.getTenantId();
        this.eventId = issuance.getEventId();
        this.caseId = issuance.getCaseId();
        this.issuanceId = issuance.getId();
        this.approvalDecisionId = issuance.getApprovalDecisionId();
        this.approvalRequestId = issuance.getApprovalRequestId();
        this.reviewReceiptId = issuance.getReviewReceiptId();
        this.reportVersionId = issuance.getReportVersionId();
        this.reportRevision = issuance.getReportRevision();
        this.reportContentSha256 = issuance.getReportContentSha256();
        this.reportTemplateVersion = issuance.getReportTemplateVersion();
        this.reportTemplateSha256 = issuance.getReportTemplateSha256();
        this.evidenceSnapshotId = issuance.getEvidenceSnapshotId();
        this.evidenceRevision = issuance.getEvidenceRevision();
        this.manifestSha256 = issuance.getManifestSha256();
        this.officialTemplateVersion = issuance.getOfficialTemplateVersion();
        this.officialTemplateSha256 = issuance.getOfficialTemplateSha256();
        this.objectKey = issuance.getCandidateObjectKey();
        this.objectVersion = issuance.getCandidateObjectVersion();
        this.pdfSha256 = issuance.getCandidatePdfSha256();
        this.pdfSize = issuance.getCandidatePdfSize();
        this.generatedAt = generatedAt;
    }
}
