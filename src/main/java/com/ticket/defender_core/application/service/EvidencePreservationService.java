package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.persistence.CaseIdempotencyRepository;
import com.ticket.defender_core.adapter.out.persistence.CaseRepository;
import com.ticket.defender_core.adapter.out.persistence.EvidencePreservationRecordRepository;
import com.ticket.defender_core.domain.Case;
import com.ticket.defender_core.domain.CaseIdempotencyRecord;
import com.ticket.defender_core.domain.EvidencePreservationAttempt;
import com.ticket.defender_core.domain.EvidencePreservationPolicy;
import com.ticket.defender_core.domain.EvidencePreservationRecord;
import com.ticket.defender_core.security.AdminAccessService;
import com.ticket.defender_core.security.AdminResourceScope;
import com.ticket.defender_core.security.AdminRole;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@Slf4j
public class EvidencePreservationService {
    private static final Pattern KEY = Pattern.compile(
            "(?i)[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    private static final Set<AdminRole> REVIEWER = Set.of(AdminRole.REVIEWER);

    private final AdminAccessService access;
    private final CaseMembershipAccess memberships;
    private final CaseRepository cases;
    private final EvidencePreservationContext context;
    private final EvidencePreservationPolicyGate gate;
    private final EvidencePreservationStorage storage;
    private final EvidencePreservationWriteService writer;
    private final EvidencePreservationRecordRepository records;
    private final CaseIdempotencyRepository idempotency;
    private final ObjectMapper json;
    private final long maximumBytes;

    public EvidencePreservationService(AdminAccessService access, CaseMembershipAccess memberships,
            CaseRepository cases, EvidencePreservationContext context, EvidencePreservationPolicyGate gate,
            EvidencePreservationStorage storage, EvidencePreservationWriteService writer,
            EvidencePreservationRecordRepository records, CaseIdempotencyRepository idempotency,
            ObjectMapper json,
            @Value("${ticket-defender.evidence-preservation.max-bytes:20971520}") long maximumBytes) {
        this.access = access;
        this.memberships = memberships;
        this.cases = cases;
        this.context = context;
        this.gate = gate;
        this.storage = storage;
        this.writer = writer;
        this.records = records;
        this.idempotency = idempotency;
        this.json = json;
        this.maximumBytes = maximumBytes;
    }

    public Result preserve(UUID caseId, Command input, String key) {
        validate(input);
        UUID actor = reviewer(caseId);
        Prepared prepared = prepare(key, input);
        EvidencePreservationRecord replay = replay(caseId, actor, prepared);
        if (replay != null) return Result.from(replay);
        EvidencePreservationContext.Checked checked = context.load(caseId, input.expectedCaseVersion(),
                input.evidenceSnapshotId(), input.sourceReferenceId(), input.evidenceId(),
                input.evidenceVerificationRecordId(), actor);
        EvidencePreservationPolicyGate.Authorized policy;
        try {
            policy = gate.authorize(checked.source(), input.preservationPolicyId(),
                    LocalDateTime.now(ZoneOffset.UTC));
        } catch (EvidencePreservationPolicyGate.Rejection rejection) {
            auditFailure(checked, input.preservationPolicyId(), rejection.policyVersion(),
                    rejection.outcome(), rejection, prepared);
            throw rejection;
        }
        String objectKey = preservationKey(checked);
        EvidencePreservationStorage.LockedObject locked;
        try {
            locked = storage.verifyLocked(objectKey, null, checked.verification().getVerifiedSha256(),
                    checked.verification().getVerifiedByteSize(), maximumBytes);
            validateStorageResult(policy, checked, locked);
        } catch (RetentionLimitException exceeded) {
            throw audited(checked, input, policy, prepared, EvidencePreservationAttempt.Outcome.STORAGE_REJECTED,
                    HttpStatus.UNPROCESSABLE_ENTITY, "PRESERVATION_RETENTION_EXCEEDED");
        } catch (EvidencePreservationStorage.ObjectLockUnsupportedException unsupported) {
            throw audited(checked, input, policy, prepared, EvidencePreservationAttempt.Outcome.STORAGE_REJECTED,
                    HttpStatus.UNPROCESSABLE_ENTITY, "PRESERVATION_STORAGE_UNSUPPORTED");
        } catch (EvidencePreservationStorage.ObjectLockNotVerifiedException unverified) {
            throw audited(checked, input, policy, prepared, EvidencePreservationAttempt.Outcome.STORAGE_REJECTED,
                    HttpStatus.UNPROCESSABLE_ENTITY, "OBJECT_LOCK_NOT_VERIFIED");
        } catch (EvidencePreservationStorage.ObjectMismatchException mismatch) {
            throw audited(checked, input, policy, prepared, EvidencePreservationAttempt.Outcome.STORAGE_REJECTED,
                    HttpStatus.UNPROCESSABLE_ENTITY, "PRESERVATION_OBJECT_MISMATCH");
        } catch (EvidencePreservationStorage.StorageUnavailableException unavailable) {
            throw audited(checked, input, policy, prepared, EvidencePreservationAttempt.Outcome.STORAGE_UNAVAILABLE,
                    HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE");
        }
        try {
            return Result.from(writer.record(caseId, input, checked, policy, locked, objectKey,
                    prepared.keyDigest(), prepared.requestDigest()));
        } catch (OptimisticLockingFailureException collision) {
            EvidencePreservationRecord committed = replay(caseId, reviewer(caseId), prepared);
            if (committed != null) return Result.from(committed);
            throw audited(checked, input, policy, prepared, EvidencePreservationAttempt.Outcome.CONFLICT,
                    HttpStatus.CONFLICT, "CASE_VERSION_CONFLICT");
        } catch (DataIntegrityViolationException collision) {
            EvidencePreservationRecord committed = replay(caseId, reviewer(caseId), prepared);
            if (committed != null) return Result.from(committed);
            throw audited(checked, input, policy, prepared, EvidencePreservationAttempt.Outcome.CONFLICT,
                    HttpStatus.CONFLICT, "PRESERVATION_ALREADY_RECORDED");
        } catch (CaseApiException collision) {
            EvidencePreservationRecord committed = replay(caseId, reviewer(caseId), prepared);
            if (committed != null) return Result.from(committed);
            throw audited(checked, input, policy, prepared, outcome(collision),
                    collision.status(), collision.code());
        } catch (DataAccessException failure) {
            throw audited(checked, input, policy, prepared, EvidencePreservationAttempt.Outcome.CONFLICT,
                    HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE");
        }
    }

    private CaseApiException audited(EvidencePreservationContext.Checked checked, Command input,
            EvidencePreservationPolicyGate.Authorized policy, Prepared prepared,
            EvidencePreservationAttempt.Outcome outcome, HttpStatus status, String code) {
        CaseApiException original = new CaseApiException(status, code);
        auditFailure(checked, input.preservationPolicyId(), policy.policy().getPolicyVersion(),
                outcome, original, prepared);
        return original;
    }

    private void auditFailure(EvidencePreservationContext.Checked checked, UUID requestedPolicyId,
            Long policyVersion, EvidencePreservationAttempt.Outcome outcome,
            CaseApiException original, Prepared prepared) {
        try {
            writer.failure(checked, requestedPolicyId, policyVersion, outcome, original.code(),
                    prepared.keyDigest(), prepared.requestDigest());
        } catch (RuntimeException auditFailure) {
            original.addSuppressed(auditFailure);
            log.error("증거 보존 실패 감사를 저장하지 못했습니다. 서비스 오류 코드: {}, 감사 실패 유형: {}",
                    original.code(), auditFailure.getClass().getSimpleName());
        }
    }

    private static EvidencePreservationAttempt.Outcome outcome(CaseApiException error) {
        return error.code().startsWith("PRESERVATION_POLICY")
                ? EvidencePreservationAttempt.Outcome.POLICY_REJECTED
                : EvidencePreservationAttempt.Outcome.CONFLICT;
    }

    private static void validateStorageResult(EvidencePreservationPolicyGate.Authorized policy,
            EvidencePreservationContext.Checked checked, EvidencePreservationStorage.LockedObject locked) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (locked == null || locked.versionId() == null || locked.versionId().isBlank()
                || locked.retainUntil() == null || !locked.retainUntil().isAfter(now))
            throw new EvidencePreservationStorage.ObjectLockNotVerifiedException();
        if (!Objects.equals(locked.sha256(), checked.verification().getVerifiedSha256())
                || locked.byteSize() != checked.verification().getVerifiedByteSize())
            throw new EvidencePreservationStorage.ObjectMismatchException();
        if (locked.lockMode() != policy.policy().getPreservationMode())
            throw new EvidencePreservationStorage.ObjectLockNotVerifiedException();
        if (locked.retainUntil().isAfter(policy.maximumRetainUntil()))
            throw new RetentionLimitException();
    }

    private EvidencePreservationRecord replay(UUID caseId, UUID actor, Prepared prepared) {
        Case target = cases.findById(caseId).orElseThrow(EvidencePreservationService::notFound);
        CaseIdempotencyRecord record = idempotency
                .findByTenantIdAndActorMembershipIdAndOperationAndTargetCaseIdAndKeyDigest(
                        target.getTenantId(), actor, "EVIDENCE_PRESERVE", caseId, prepared.keyDigest())
                .orElse(null);
        if (record == null) return null;
        if (!record.getRequestDigest().equals(prepared.requestDigest()))
            throw new CaseApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED");
        UUID resultId = json.readValue(record.getResponseJson(), RecordReference.class).recordId();
        EvidencePreservationRecord result = records.findById(resultId)
                .orElseThrow(EvidencePreservationService::notFound);
        if (!caseId.equals(result.getCaseId()) || !target.getTenantId().equals(result.getTenantId())
                || !target.getEventId().equals(result.getEventId())) throw notFound();
        return result;
    }

    private UUID reviewer(UUID caseId) {
        Case target = cases.findById(caseId).orElseThrow(EvidencePreservationService::notFound);
        var actor = access.requireResourceAccess(
                new AdminResourceScope(target.getTenantId(), target.getEventId()), null, REVIEWER);
        return memberships.actor(actor).getId();
    }

    private Prepared prepare(String key, Command input) {
        if (key == null || !KEY.matcher(key).matches())
            throw new CaseApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY");
        return new Prepared(CaseSnapshotManifest.sha256(key.toLowerCase()),
                CaseSnapshotManifest.sha256(json.writeValueAsString(input)));
    }

    private static String preservationKey(EvidencePreservationContext.Checked checked) {
        return "preserved-evidence/" + checked.source().target().getTenantId() + "/"
                + checked.source().target().getEventId() + "/" + checked.source().target().getId()
                + "/" + checked.source().asset().getEvidenceId();
    }

    private static void validate(Command input) {
        if (input == null || input.expectedCaseVersion() == null || input.evidenceSnapshotId() == null
                || input.sourceReferenceId() == null || input.evidenceId() == null
                || input.evidenceVerificationRecordId() == null || input.preservationPolicyId() == null)
            throw new CaseApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED");
        CasePreparationWriteService.validate(input.reason(), 3, 1800);
    }

    private static CaseApiException notFound() {
        return new CaseApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }

    public record Command(Long expectedCaseVersion, UUID evidenceSnapshotId, UUID sourceReferenceId,
                          UUID evidenceId, UUID evidenceVerificationRecordId,
                          UUID preservationPolicyId, String reason) { }

    public record Result(UUID recordId, UUID caseId, UUID evidenceSnapshotId, UUID sourceReferenceId,
                         UUID evidenceId, UUID evidenceVerificationRecordId, UUID preservationPolicyId,
                         long preservationPolicyVersion, String storageProfile, String actualVersionId,
                         EvidencePreservationPolicy.Mode objectLockMode, LocalDateTime retainUntil,
                         EvidencePreservationRecord.Status preservationStatus, LocalDateTime verifiedAt) {
        static Result from(EvidencePreservationRecord record) {
            return new Result(record.getId(), record.getCaseId(), record.getEvidenceSnapshotId(),
                    record.getSourceReferenceId(), record.getEvidenceId(),
                    record.getEvidenceVerificationRecordId(), record.getPreservationPolicyId(),
                    record.getPreservationPolicyVersion(), record.getStorageProfile(),
                    record.getActualVersionId(), record.getObjectLockMode(), record.getRetainUntil(),
                    record.getPreservationStatus(), record.getVerifiedAt());
        }
    }

    private record Prepared(String keyDigest, String requestDigest) { }
    private record RecordReference(UUID recordId) { }
    private static class RetentionLimitException extends EvidencePreservationStorage.ObjectLockNotVerifiedException { }
}
