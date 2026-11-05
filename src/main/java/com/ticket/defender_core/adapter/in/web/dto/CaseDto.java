package com.ticket.defender_core.adapter.in.web.dto;

import com.ticket.defender_core.domain.Case;
import com.ticket.defender_core.domain.CaseActivity;
import com.ticket.defender_core.domain.CaseSourceReference;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class CaseDto {
    private CaseDto() { }

    public enum DismissalBasis { EVIDENCE_REFUTES_ALLEGATION, NO_VIOLATION_FOUND_AFTER_REVIEW }

    public record GraphCluster(String analysisRequestId, String clusterId) { }

    public record Create(UUID eventId, Case.Type type, List<GraphCluster> graphClusters,
                         List<UUID> fanReportIds, List<UUID> evidenceIds, List<UUID> candidateRunIds, String reason,
                         UUID relatedCaseId, String relationReason) {
        public Create(UUID eventId, Case.Type type, List<GraphCluster> graphClusters,
                List<UUID> fanReportIds, List<UUID> evidenceIds, String reason,
                UUID relatedCaseId, String relationReason) {
            this(eventId, type, graphClusters, fanReportIds, evidenceIds, null, reason, relatedCaseId, relationReason);
        }
    }

    public record Transition(Case.Status toStatus, Long expectedCaseVersion, String reason,
                             String classification, String unassignedReason,
                             String missingEvidence, String requestedAction,
                             String unavailableReason, DismissalBasis dismissalBasis) { }

    public record Assign(UUID assigneeMembershipId, Long expectedCaseVersion, String reason) { }

    public record View(UUID caseId, UUID eventId, Case.Type type, Case.Status status,
                       long caseVersion, long evidenceRevision, UUID createdByMembershipId,
                       UUID assigneeMembershipId, UUID relatedCaseId, String relationReason,
                       String classification, UUID currentReportVersionId,
                       OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        public static View from(Case value) {
            return new View(value.getId(), value.getEventId(), value.getType(), value.getStatus(),
                    value.getCaseVersion(), value.getEvidenceRevision(), value.getCreatedByMembershipId(),
                    value.getAssigneeMembershipId(), value.getRelatedCaseId(), value.getRelationReason(),
                    value.getClassification(), value.getCurrentReportVersionId(),
                    utc(value.getCreatedAt()), utc(value.getUpdatedAt()));
        }
    }

    public record Page(List<View> items, int page, int pageSize, boolean hasNext) { }

    public record Source(UUID sourceReferenceId, CaseSourceReference.Type sourceType,
                         String analysisRequestId, String clusterId, Integer storedMemberCount,
                         String analysisAlgorithm, String analysisAlgorithmVersion,
                         OffsetDateTime analysisCompletedAt, Double riskScore,
                         UUID fanReportId, UUID evidenceId, UUID candidateRunId, UUID candidateAnalysisId,
                         Long candidateAnalysisRowVersion, String candidateAnalysisResponseSha256,
                         String candidateEvidenceSha256, UUID candidatePerformanceLinkId,
                         Integer candidatePerformanceLinkRevision, UUID candidateVerifiedPerformanceId,
                         Integer candidateVerifiedPerformanceRevision, UUID candidateReservationSourceId,
                         Integer candidateReservationSourceRevision, UUID candidateReservationBatchId,
                         String candidateReservationSourceVersion, String candidateStatus, String candidateReason,
                         Integer candidateCount, UUID candidateId, String candidateApplicability,
                         OffsetDateTime linkedAt) {
        public static Source from(CaseSourceReference value) {
            return from(value, null);
        }

        public static Source from(CaseSourceReference value, String applicability) {
            return new Source(value.getId(), value.getSourceType(), value.getAnalysisRequestId(),
                    value.getClusterId(), value.getStoredMemberCount(), value.getAnalysisAlgorithm(),
                    value.getAnalysisAlgorithmVersion(), utc(value.getAnalysisCompletedAt()),
                    value.getRiskScore(), value.getFanReportId(), value.getEvidenceId(),
                    value.getListingCandidateMatchRunId(), value.getCandidateAnalysisId(),
                    value.getCandidateAnalysisRowVersion(), value.getCandidateAnalysisResponseSha256(),
                    value.getCandidateEvidenceSha256(), value.getCandidatePerformanceLinkId(),
                    value.getCandidatePerformanceLinkRevision(), value.getCandidateVerifiedPerformanceId(),
                    value.getCandidateVerifiedPerformanceRevision(), value.getCandidateReservationSourceId(),
                    value.getCandidateReservationSourceRevision(), value.getCandidateReservationBatchId(),
                    value.getCandidateReservationSourceVersion(), value.getCandidateStatus(),
                    value.getCandidateReason(), value.getCandidateCount(), value.getCandidateId(), applicability,
                    utc(value.getLinkedAt()));
        }
    }

    public record Activity(UUID activityId, String eventType, UUID actorMembershipId, String actorRole,
                           String reason, String fromStatus, String toStatus,
                           Long versionBefore, long versionAfter, long evidenceRevision,
                           UUID sourceReferenceId, UUID previousAssigneeMembershipId,
                           UUID newAssigneeMembershipId, OffsetDateTime occurredAt) {
        public static Activity from(CaseActivity value) {
            return new Activity(value.getId(), value.getEventType(), value.getActorMembershipId(),
                    value.getActorRole(),
                    value.getReason(), value.getFromStatus(), value.getToStatus(),
                    value.getVersionBefore(), value.getVersionAfter(), value.getEvidenceRevision(),
                    value.getSourceReferenceId(), value.getPreviousAssigneeMembershipId(),
                    value.getNewAssigneeMembershipId(), utc(value.getOccurredAt()));
        }
    }

    public record Activities(List<Activity> items, int page, int pageSize, boolean hasNext) { }

    private static OffsetDateTime utc(LocalDateTime time) {
        return time == null ? null : time.atOffset(ZoneOffset.UTC);
    }
}
