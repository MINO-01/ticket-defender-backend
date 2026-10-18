package com.ticket.defender_core.security;

import java.util.UUID;

/** Tenant and event IDs read from the stored resource, not supplied by its caller. */
public record AdminResourceScope(UUID tenantId, UUID eventId) {
}
