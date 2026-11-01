package com.ticket.defender_core.security;

import java.util.Optional;
import java.util.UUID;

public interface AdminMembershipLookup {
    Optional<AdminMembership> findActiveMembership(String subject, UUID tenantId);

    /** 비활성 고객사와 비활성 멤버십을 제외하고 서로 다른 활성 고객사 2곳 이상에 소속되었는지 확인합니다. */
    boolean hasActiveMembershipsInMultipleTenants(String subject);
}
