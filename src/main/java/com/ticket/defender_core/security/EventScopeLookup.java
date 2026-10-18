package com.ticket.defender_core.security;

import java.util.Optional;
import java.util.UUID;

public interface EventScopeLookup {
    Optional<UUID> findTenantId(UUID eventId);
}
