package com.ticket.defender_core.security;

import java.util.UUID;

/** Stable authenticated subject for audit records and later self-approval checks. */
public record AdminActor(String subject, UUID tenantId, UUID eventId) {
}
