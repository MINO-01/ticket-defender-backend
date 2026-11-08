package com.ticket.defender_core.adapter.in.web.dto;

import com.ticket.defender_core.domain.ApprovalAuditEvent;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** No review receipt token, manifest/body hash or object-storage information is returned. */
public final class ApprovalAuditDto {
    private ApprovalAuditDto() { }

    public record Item(UUID approvalAuditEventId, UUID approvalRequestId, UUID approvalDecisionId,
                       UUID reportVersionId, UUID evidenceSnapshotId, UUID actorMembershipId,
                       String eventType, String fromStatus, String toStatus,
                       long evidenceRevision, long caseVersionBefore, long caseVersionAfter,
                       String reason, OffsetDateTime occurredAt) {
        public static Item from(ApprovalAuditEvent value) {
            return new Item(value.getId(), value.getApprovalRequestId(), value.getApprovalDecisionId(),
                    value.getReportVersionId(), value.getEvidenceSnapshotId(), value.getActorMembershipId(),
                    value.getEventType(), value.getFromStatus(), value.getToStatus(), value.getEvidenceRevision(),
                    value.getCaseVersionBefore(), value.getCaseVersionAfter(), value.getReason(),
                    value.getOccurredAt().atOffset(ZoneOffset.UTC));
        }
    }

    public record Page(List<Item> items, int page, int pageSize, boolean hasNext) { }
}
