package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.EvidenceIntegrityDto;
import com.ticket.defender_core.adapter.out.persistence.CaseActivityRepository;
import com.ticket.defender_core.adapter.out.persistence.CaseIdempotencyRepository;
import com.ticket.defender_core.adapter.out.persistence.CaseRepository;
import com.ticket.defender_core.adapter.out.persistence.EvidenceVerificationRecordRepository;
import com.ticket.defender_core.domain.Case;
import com.ticket.defender_core.domain.CaseActivity;
import com.ticket.defender_core.domain.CaseIdempotencyRecord;
import com.ticket.defender_core.domain.EvidenceVerificationRecord;
import com.ticket.defender_core.security.AdminRole;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EvidenceIntegrityWriteService {
    private final EvidenceIntegrityContext context;
    private final CaseRepository cases;
    private final EvidenceVerificationRecordRepository records;
    private final CaseActivityRepository activities;
    private final CaseIdempotencyRepository idempotency;
    private final ObjectMapper json;

    @Transactional
    public EvidenceVerificationRecord record(UUID caseId, EvidenceIntegrityDto.VerifyInput input,
            EvidenceIntegrityContext.Checked prepared, EvidenceIntegrityService.Outcome outcome,
            String keyDigest, String requestDigest) {
        EvidenceIntegrityContext.Checked current = context.load(caseId, input.evidenceSnapshotId(),
                input.sourceReferenceId(), input.evidenceId(), input.expectedCaseVersion(),
                prepared.actorMembershipId());
        if (!prepared.fingerprint().equals(current.fingerprint()))
            throw new CaseApiException(HttpStatus.CONFLICT, "SOURCE_CHANGED");
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (current.expired(now) && outcome.status() != EvidenceVerificationRecord.Status.RETENTION_EXPIRED)
            throw new CaseApiException(HttpStatus.UNPROCESSABLE_ENTITY, "RETENTION_EXPIRED");
        Case target = current.target();
        long before = target.getCaseVersion();
        target.recordPreparation(now);
        cases.saveAndFlush(target);
        EvidenceVerificationRecord result = records.saveAndFlush(new EvidenceVerificationRecord(
                target, current.snapshot(), current.source(), current.asset(),
                CaseSnapshotManifest.sha256(current.asset().getObjectKey()), outcome.marker(),
                outcome.markerKind(), outcome.sha256(), outcome.byteSize(), current.retentionUntil(),
                outcome.status(), current.actorMembershipId(), now));
        activities.saveAndFlush(new CaseActivity(target, current.actorMembershipId(), AdminRole.REVIEWER.name(),
                "EVIDENCE_BYTES_CHECKED", "verification=" + result.getId() + "; status="
                + result.getVerificationStatus() + "; " + input.reason().trim(),
                target.getStatus(), target.getStatus(), before, target.getCaseVersion(),
                current.source().getId(), now));
        idempotency.saveAndFlush(new CaseIdempotencyRecord(target.getTenantId(), current.actorMembershipId(),
                "EVIDENCE_VERIFY", caseId, keyDigest, requestDigest, caseId,
                json.writeValueAsString(Map.of("recordId", result.getId())), now));
        return result;
    }
}
