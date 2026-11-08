package com.ticket.defender_core.adapter.in.web.dto;

import com.ticket.defender_core.domain.ApprovalDecision;
import com.ticket.defender_core.domain.ApprovalRequest;
import com.ticket.defender_core.domain.OfficialPdfIssuance;
import com.ticket.defender_core.domain.ReportVersion;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Read-only references for navigating a Case through report, approval and official-PDF records. */
public final class CaseWorkflowDto {
    private CaseWorkflowDto() { }

    public record NextStep(String code, String description) { }

    public record Report(UUID reportVersionId, UUID evidenceSnapshotId, long evidenceRevision,
                         long revision, long version, ReportVersion.Status status) { }

    public record Approval(UUID approvalRequestId, ApprovalRequest.Status requestStatus,
                           UUID approvalDecisionId, ApprovalDecision.Outcome decisionOutcome,
                           OffsetDateTime decidedAt) { }

    public record OfficialPdf(UUID officialPdfIssuanceId, OfficialPdfIssuance.Status status,
                              String lastFailureCode) { }

    public record View(CaseDto.View investigation, NextStep nextStep, Report report,
                       Approval approval, OfficialPdf officialPdf) { }
}
