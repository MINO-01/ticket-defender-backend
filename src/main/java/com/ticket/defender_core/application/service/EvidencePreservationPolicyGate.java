package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.persistence.EvidencePreservationPolicyRepository;
import com.ticket.defender_core.domain.EvidencePreservationAttempt;
import com.ticket.defender_core.domain.EvidencePreservationPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

@Component
public class EvidencePreservationPolicyGate {
    private final EvidencePreservationPolicyRepository policies;
    private final EvidencePreservationStorage storage;
    private final boolean enabled;

    public EvidencePreservationPolicyGate(EvidencePreservationPolicyRepository policies,
            EvidencePreservationStorage storage,
            @Value("${ticket-defender.evidence-preservation.enabled:false}") boolean enabled) {
        this.policies = policies;
        this.storage = storage;
        this.enabled = enabled;
    }

    public Authorized authorize(EvidenceIntegrityContext.Checked checked, UUID policyId, LocalDateTime now) {
        EvidencePreservationPolicy policy = policies.findTopByPolicyIdOrderByPolicyVersionDesc(policyId)
                .orElseThrow(() -> rejected(HttpStatus.CONFLICT, "PRESERVATION_POLICY_NOT_APPROVED", null));
        if (!checked.target().getTenantId().equals(policy.getTenantId())
                || (policy.getEventId() != null && !checked.target().getEventId().equals(policy.getEventId())))
            throw rejected(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", policy.getPolicyVersion());
        if (policy.getPolicyStatus() != EvidencePreservationPolicy.Status.APPROVED
                || policy.getApprovedAt() == null || policy.getApprovalAuthority() == null
                || policy.getApprovalAuthority().isBlank() || policy.getApprovedAt().isAfter(now)
                || policy.getEffectiveFrom().isAfter(now) || !policy.getEffectiveUntil().isAfter(now))
            throw rejected(HttpStatus.CONFLICT, "PRESERVATION_POLICY_NOT_APPROVED", policy.getPolicyVersion());
        if (!enabled)
            throw rejected(HttpStatus.CONFLICT, "PRESERVATION_DISABLED", policy.getPolicyVersion());
        if (!policy.getStorageProfile().equals(storage.profile()))
            throw rejected(HttpStatus.UNPROCESSABLE_ENTITY, "PRESERVATION_STORAGE_UNSUPPORTED",
                    policy.getPolicyVersion());
        if (checked.expired(now))
            throw rejected(HttpStatus.UNPROCESSABLE_ENTITY, "RETENTION_EXPIRED", policy.getPolicyVersion());
        LocalDateTime maximum = minimum(checked.retentionUntil(), policy.getEffectiveUntil(),
                now.plusDays(policy.getRetentionLimitDays()));
        if (!maximum.isAfter(now))
            throw rejected(HttpStatus.UNPROCESSABLE_ENTITY, "RETENTION_EXPIRED", policy.getPolicyVersion());
        return new Authorized(policy, maximum);
    }

    private static LocalDateTime minimum(LocalDateTime first, LocalDateTime second, LocalDateTime third) {
        LocalDateTime result = first.isBefore(second) ? first : second;
        return result.isBefore(third) ? result : third;
    }

    private static Rejection rejected(HttpStatus status, String code, Long version) {
        return new Rejection(status, code, version);
    }

    public record Authorized(EvidencePreservationPolicy policy, LocalDateTime maximumRetainUntil) { }

    public static class Rejection extends CaseApiException {
        private final Long policyVersion;

        Rejection(HttpStatus status, String code, Long policyVersion) {
            super(status, code);
            this.policyVersion = policyVersion;
        }

        public Long policyVersion() { return policyVersion; }
        public EvidencePreservationAttempt.Outcome outcome() {
            return EvidencePreservationAttempt.Outcome.POLICY_REJECTED;
        }
    }
}
