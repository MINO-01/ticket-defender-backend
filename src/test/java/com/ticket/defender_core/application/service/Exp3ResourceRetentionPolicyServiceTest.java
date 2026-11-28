package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.Exp3ResourceRetentionPolicyDto;
import com.ticket.defender_core.security.AdminRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class Exp3ResourceRetentionPolicyServiceTest {
    private final UUID tenantId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();
    private final UUID membershipId = UUID.randomUUID();
    private CandidatePipelineAccess access;
    private JdbcTemplate jdbc;
    private Exp3ResourceRetentionPolicyService service;

    @BeforeEach
    void setup() {
        access = mock(CandidatePipelineAccess.class);
        jdbc = mock(JdbcTemplate.class);
        service = new Exp3ResourceRetentionPolicyService(access, jdbc);
        when(access.event(eq(eventId), eq(Set.of(AdminRole.TENANT_ADMIN))))
                .thenReturn(new CandidatePipelineAccess.Scope(tenantId, eventId, membershipId, null));
        when(jdbc.queryForList(anyString(), eq(String.class), any(), any()))
                .thenReturn(List.of(eventId.toString()));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), (Object[]) any())).thenReturn(0);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    }

    @Test
    void createsRefundRetentionApprovalWithTenantAdminAttribution() {
        OffsetDateTime from = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        var result = service.approve(eventId, new Exp3ResourceRetentionPolicyDto.Create(
                "SUPPLIER_CODE", "provider-policy-v2", 30, from, null,
                "provider retention policy", "tenant admin verified policy version and dates"));

        assertThat(result.resourceType()).isEqualTo("refund_account_hash");
        assertThat(result.approvedByMembershipId()).isEqualTo(membershipId);
        assertThat(result.policyVersion()).isEqualTo("provider-policy-v2");
        assertThat(result.retentionDays()).isEqualTo(30);
        verify(access).event(eventId, Set.of(AdminRole.TENANT_ADMIN));
        verify(jdbc).update(anyString(), any(Object[].class));
    }

    @Test
    void rejectsOverlappingOrMalformedPolicyBeforeDatabaseWrites() {
        OffsetDateTime from = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        var malformed = new Exp3ResourceRetentionPolicyDto.Create(
                "SUPPLIER_CODE", "provider-policy-v2", 0, from, null, "source", "basis");

        assertThatThrownBy(() -> service.approve(eventId, malformed))
                .isInstanceOf(CaseApiException.class)
                .satisfies(error -> assertThat(((CaseApiException) error).code()).isEqualTo("INVALID_REQUEST"));
        verifyNoInteractions(jdbc);
    }
}
