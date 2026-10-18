package com.ticket.defender_core.security;

import com.ticket.defender_core.adapter.in.web.dto.AgentAnalysisRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AnalysisIngestAccessServiceTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Mock private ServiceTenantLookup serviceTenants;
    @Mock private EventReferenceLookup events;
    @InjectMocks private AnalysisIngestAccessService access;

    @AfterEach
    void clearIdentity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void missingAuthenticationCannotReachTenantLookup() {
        assertThatThrownBy(() -> access.requireScope(request()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verifyNoInteractions(serviceTenants, events);
    }

    @Test
    void missingBindingFailsClosed() {
        authenticate();
        given(serviceTenants.findActiveTenantId(anyString(), anyString(), anyString()))
                .willReturn(Optional.empty());
        assertThatThrownBy(() -> access.requireScope(request()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(events);
    }

    @Test
    void databaseFailureCannotBecomeAuthorization() {
        authenticate();
        given(serviceTenants.findActiveTenantId(anyString(), anyString(), anyString()))
                .willThrow(new DataAccessResourceFailureException("database unavailable"));
        assertThatThrownBy(() -> access.requireScope(request()))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verifyNoInteractions(events);
    }

    @Test
    void eventLookupFailureCannotBecomeAuthorization() {
        authenticate();
        given(serviceTenants.findActiveTenantId(anyString(), anyString(), anyString()))
                .willReturn(Optional.of(TENANT));
        given(events.findActiveEventId(TENANT, "event-1"))
                .willThrow(new DataAccessResourceFailureException("database unavailable"));
        assertThatThrownBy(() -> access.requireScope(request()))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    private void authenticate() {
        Jwt jwt = Jwt.withTokenValue("test-service-token")
                .header("alg", "RS256")
                .issuer("https://issuer.example.test/service")
                .subject("ingest-a")
                .claim("client_id", "trusted-ingest")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt);
        authentication.setAuthenticated(true);
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(jwt.getIssuer()).isNotNull();
        assertThat(jwt.getSubject()).isEqualTo("ingest-a");
        assertThat(jwt.<String>getClaim("client_id")).isEqualTo("trusted-ingest");
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private AgentAnalysisRequest request() {
        return new AgentAnalysisRequest(List.of(new AgentAnalysisRequest.TicketData(
                "account-1", "reservation-1", "event-1", "a".repeat(64), null, null, null)));
    }
}
