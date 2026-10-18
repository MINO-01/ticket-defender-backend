package com.ticket.defender_core.security;

import com.ticket.defender_core.adapter.in.web.dto.AgentAnalysisRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AnalysisIngestAccessService {
    private final ServiceTenantLookup serviceTenants;
    private final EventReferenceLookup events;

    public AnalysisIngestScope requireScope(AgentAnalysisRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwt) || !authentication.isAuthenticated()
                || jwt.getToken().getIssuer() == null || jwt.getToken().getSubject() == null
                || jwt.getToken().getSubject().isBlank()
                || !(jwt.getToken().getClaim("client_id") instanceof String clientId)
                || clientId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }

        UUID tenantId = serviceTenants.findActiveTenantId(
                        jwt.getToken().getIssuer().toString(), clientId, jwt.getToken().getSubject())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
        Map<String, UUID> eventIds = new HashMap<>();
        for (AgentAnalysisRequest.TicketData ticket : request.tickets()) {
            eventIds.computeIfAbsent(ticket.eventId(), externalEventRef ->
                    events.findActiveEventId(tenantId, externalEventRef)
                            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
        }
        return new AnalysisIngestScope(tenantId, eventIds);
    }
}
