package com.ticket.defender_core.security;

import java.util.Optional;
import java.util.UUID;

public interface EventReferenceLookup {
    Optional<UUID> findActiveEventId(UUID tenantId, String externalEventRef);
}
