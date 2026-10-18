package com.ticket.defender_core.security;

import java.util.Optional;
import java.util.UUID;

public interface AdminMembershipLookup {
    Optional<AdminMembership> findActiveMembership(String subject, UUID tenantId);
}
