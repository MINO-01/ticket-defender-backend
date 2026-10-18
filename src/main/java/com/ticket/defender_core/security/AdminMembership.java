package com.ticket.defender_core.security;

import java.util.Set;
import java.util.UUID;

/** Membership data must come from a server-controlled store, never a JWT or request body. */
public record AdminMembership(String subject, UUID tenantId, Set<AdminRole> roles, Set<UUID> eventIds) {
    public AdminMembership {
        roles = Set.copyOf(roles);
        eventIds = Set.copyOf(eventIds);
    }
}
