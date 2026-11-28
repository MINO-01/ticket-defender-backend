package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.CaseDto;
import com.ticket.defender_core.adapter.out.persistence.MultiEventCandidateCaseSourceReader;
import com.ticket.defender_core.domain.Case;
import com.ticket.defender_core.domain.CaseSourceReference;
import com.ticket.defender_core.security.AdminAccessService;
import com.ticket.defender_core.security.AdminRole;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class MultiEventCaseSourceVerifier {
    private static final Set<AdminRole> CREATE_ROLES = Set.of(AdminRole.ANALYST, AdminRole.REVIEWER);
    private static final Set<AdminRole> VIEW_ROLES = Set.of(AdminRole.TENANT_ADMIN, AdminRole.ANALYST, AdminRole.REVIEWER);
    private static final Pattern CANDIDATE_ID = Pattern.compile("multi-[a-f0-9]{64}");

    private final MultiEventCandidateCaseSourceReader candidates;
    private final AdminAccessService access;

    public List<CaseSourceReference> verify(Case target, List<CaseDto.MultiEventCandidate> inputs,
            LocalDateTime now) {
        if (inputs == null || inputs.isEmpty()) return List.of();
        if (inputs.size() > 30 || inputs.stream().anyMatch(input -> input == null
                || input.runId() == null || input.candidateId() == null
                || !CANDIDATE_ID.matcher(input.candidateId()).matches())) throw invalid();

        List<CaseSourceReference> result = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (CaseDto.MultiEventCandidate input : inputs) {
            MultiEventCandidateCaseSourceReader.CompletedCandidate completed = candidates
                    .findCompletedCandidate(target.getTenantId(), input.runId(), input.candidateId())
                    .orElseThrow(MultiEventCaseSourceVerifier::notFound);
            requireScopeAccess(target, input.runId(), CREATE_ROLES);

            String digest = CaseSourceVerifier.digest(target, "MULTI_EVENT_CANDIDATE",
                    input.runId().toString(), input.candidateId());
            CaseSourceVerifier.unique(keys, digest);
            result.add(CaseSourceReference.multiEventCandidate(target, digest, input.runId(),
                    input.candidateId(), completed.completedAt(), now));
        }
        return result;
    }

    public void requireCreatable(UUID tenantId, UUID caseEventId, UUID runId, String candidateId) {
        if (runId == null || candidateId == null || !CANDIDATE_ID.matcher(candidateId).matches()) throw invalid();
        candidates.findCompletedCandidate(tenantId, runId, candidateId)
                .orElseThrow(MultiEventCaseSourceVerifier::notFound);
        requireScopeAccess(tenantId, caseEventId, runId, CREATE_ROLES);
    }

    public void requireReadable(Case target, UUID runId, String candidateId) {
        if (runId == null || candidateId == null || !CANDIDATE_ID.matcher(candidateId).matches()) throw notFound();
        candidates.findCompletedCandidate(target.getTenantId(), runId, candidateId)
                .orElseThrow(MultiEventCaseSourceVerifier::notFound);
        requireScopeAccess(target.getTenantId(), target.getEventId(), runId, VIEW_ROLES);
    }

    private void requireScopeAccess(Case target, UUID runId, Set<AdminRole> roles) {
        requireScopeAccess(target.getTenantId(), target.getEventId(), runId, roles);
    }

    private void requireScopeAccess(UUID tenantId, UUID caseEventId, UUID runId, Set<AdminRole> roles) {
        List<UUID> eventIds = candidates.findEventIds(tenantId, runId);
        if (eventIds.isEmpty() || !eventIds.contains(caseEventId)) throw notFound();
        for (UUID eventId : eventIds) {
            if (!tenantId.equals(access.requireEventAccess(eventId, roles).tenantId())) throw notFound();
        }
    }

    private static CaseApiException invalid() {
        return new CaseApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED");
    }

    private static CaseApiException notFound() {
        return new CaseApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }
}
