package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.persistence.CaseActivityRepository;
import com.ticket.defender_core.adapter.out.persistence.CaseIdempotencyRepository;
import com.ticket.defender_core.adapter.out.persistence.CaseRepository;
import com.ticket.defender_core.adapter.out.persistence.EvidencePreservationAttemptRepository;
import com.ticket.defender_core.adapter.out.persistence.EvidencePreservationRecordRepository;
import com.ticket.defender_core.domain.Case;
import com.ticket.defender_core.domain.CaseActivity;
import com.ticket.defender_core.domain.CaseIdempotencyRecord;
import com.ticket.defender_core.domain.EvidencePreservationAttempt;
import com.ticket.defender_core.domain.EvidencePreservationRecord;
import com.ticket.defender_core.security.AdminRole;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EvidencePreservationWriteService {
    private final EvidencePreservationContext context;
    private final EvidencePreservationPolicyGate gate;
    private final CaseRepository cases;
    private final EvidencePreservationRecordRepository records;
    private final EvidencePreservationAttemptRepository attempts;
    private final CaseActivityRepository activities;
    private final CaseIdempotencyRepository idempotency;
    private final ObjectMapper json;

    @Transactional
    public EvidencePreservationRecord record(UUID caseId, EvidencePreservationService.Command input,
            EvidencePreservationContext.Checked prepared,
            EvidencePreservationPolicyGate.Authorized preparedPolicy,
            EvidencePreservationStorage.LockedObject locked, String objectKey,
            String keyDigest, String requestDigest) {
        EvidencePreservationContext.Checked current = context.load(caseId, input.expectedCaseVersion(),
                input.evidenceSnapshotId(), input.sourceReferenceId(), input.evidenceId(),
                input.evidenceVerificationRecordId(), prepared.source().actorMembershipId());
        if (!prepared.source().fingerprint().equals(current.source().fingerprint()))
            throw new CaseApiException(HttpStatus.CONFLICT, "SOURCE_CHANGED");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        EvidencePreservationPolicyGate.Authorized currentPolicy = gate.authorize(
                current.source(), input.preservationPolicyId(), now);
        if (!preparedPolicy.policy().getId().equals(currentPolicy.policy().getId()))
            throw new CaseApiException(HttpStatus.CONFLICT, "PRESERVATION_POLICY_CHANGED");
        validateLock(currentPolicy, locked, now);
        if (records.findByTenantIdAndEvidenceIdAndStorageProfileAndActualVersionIdAndPreservationPolicyIdAndPreservationPolicyVersion(
                current.source().target().getTenantId(), current.source().asset().getEvidenceId(),
                currentPolicy.policy().getStorageProfile(), locked.versionId(),
                currentPolicy.policy().getPolicyId(), currentPolicy.policy().getPolicyVersion()).isPresent())
            throw new CaseApiException(HttpStatus.CONFLICT, "PRESERVATION_ALREADY_RECORDED");

        Case target = current.source().target();
        long before = target.getCaseVersion();
        target.recordPreparation(now);
        cases.saveAndFlush(target);
        EvidencePreservationRecord result = records.saveAndFlush(new EvidencePreservationRecord(
                target, current.source().snapshot(), current.source().source(), current.source().asset(),
                current.verification(), currentPolicy.policy(), currentPolicy.policy().getStorageProfile(),
                CaseSnapshotManifest.sha256(objectKey), locked.versionId(), locked.sha256(),
                locked.byteSize(), locked.lockMode(), locked.retainUntil(),
                current.source().actorMembershipId(), now));
        activities.saveAndFlush(new CaseActivity(target, current.source().actorMembershipId(),
                AdminRole.REVIEWER.name(), "EVIDENCE_PRESERVATION_VERIFIED",
                "preservation=" + result.getId() + "; status=" + result.getPreservationStatus()
                        + "; " + input.reason().trim(),
                target.getStatus(), target.getStatus(), before, target.getCaseVersion(),
                current.source().source().getId(), now));
        attempts.saveAndFlush(new EvidencePreservationAttempt(target, current.source().snapshot(),
                current.source().source(), current.source().asset(), current.verification(),
                input.preservationPolicyId(), currentPolicy.policy().getPolicyVersion(), result.getId(),
                current.source().actorMembershipId(), EvidencePreservationAttempt.Outcome.VERIFIED,
                "IMMUTABLE_VERIFIED", keyDigest, requestDigest, now));
        idempotency.saveAndFlush(new CaseIdempotencyRecord(target.getTenantId(),
                current.source().actorMembershipId(), "EVIDENCE_PRESERVE", caseId, keyDigest,
                requestDigest, caseId, json.writeValueAsString(Map.of("recordId", result.getId())), now));
        return result;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failure(EvidencePreservationContext.Checked checked, UUID requestedPolicyId,
            Long resolvedPolicyVersion, EvidencePreservationAttempt.Outcome outcome, String reasonCode,
            String keyDigest, String requestDigest) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        attempts.saveAndFlush(new EvidencePreservationAttempt(checked.source().target(),
                checked.source().snapshot(), checked.source().source(), checked.source().asset(),
                checked.verification(), requestedPolicyId, resolvedPolicyVersion, null,
                checked.source().actorMembershipId(), outcome, reasonCode, keyDigest, requestDigest, now));
    }

    private static void validateLock(EvidencePreservationPolicyGate.Authorized policy,
            EvidencePreservationStorage.LockedObject locked, LocalDateTime now) {
        if (locked == null || locked.versionId() == null || locked.versionId().isBlank()
                || locked.retainUntil() == null || !locked.retainUntil().isAfter(now))
            throw new CaseApiException(HttpStatus.UNPROCESSABLE_ENTITY, "OBJECT_LOCK_NOT_VERIFIED");
        if (locked.lockMode() != policy.policy().getPreservationMode())
            throw new CaseApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PRESERVATION_MODE_NOT_ALLOWED");
        if (locked.retainUntil().isAfter(policy.maximumRetainUntil()))
            throw new CaseApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PRESERVATION_RETENTION_EXCEEDED");
    }
}
