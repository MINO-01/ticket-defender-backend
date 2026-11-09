package com.ticket.defender_core.adapter.in.web.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 관리자 화면용 조회 계약입니다. 저장된 행만 반영하며 사유 원문과 내부 해시는 응답하지 않습니다. */
public final class AdminConsoleDto {
    private AdminConsoleDto() { }

    public record Me(UUID tenantId, List<String> roles, List<UUID> eventIds) { }

    public record ApprovalRequestItem(UUID approvalRequestId, UUID caseId, UUID reportVersionId, long reportRevision,
                                      long evidenceRevision, String status, OffsetDateTime requestedAt) { }

    public record AuditEventItem(UUID auditEventId, UUID caseId, UUID approvalRequestId, UUID actorMembershipId,
                                 String eventType, String fromStatus, String toStatus, OffsetDateTime occurredAt) { }

    public record Summary(UUID eventId, Map<String, Long> casesByStatus, Map<String, Long> approvalRequestsByStatus,
                          Map<String, Long> track1QuantityRunsByStatus, String coverage) { }

    public record Page<T>(List<T> items, int page, int pageSize, boolean hasNext) { }
}
