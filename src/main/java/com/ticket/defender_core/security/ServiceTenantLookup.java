package com.ticket.defender_core.security;

import java.util.Optional;
import java.util.UUID;

public interface ServiceTenantLookup {
    Optional<UUID> findActiveTenantId(String issuer, String clientId, String subject);
}
