package com.ticket.defender_core.adapter.in.web.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class Exp3ResourceRetentionPolicyDto {
    private Exp3ResourceRetentionPolicyDto() { }

    public record Create(String supplierNamespace, String policyVersion, Integer retentionDays,
                         OffsetDateTime effectiveFrom, OffsetDateTime effectiveUntil,
                         String policySource, String verificationBasis) { }

    public record View(UUID id, UUID eventId, String supplierNamespace, String resourceType,
                       String policyVersion, int retentionDays, OffsetDateTime effectiveFrom,
                       OffsetDateTime effectiveUntil, String policySource, String verificationBasis,
                       UUID approvedByMembershipId, OffsetDateTime approvedAt) { }
}
