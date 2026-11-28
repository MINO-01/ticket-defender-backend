package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.CaseDto;
import com.ticket.defender_core.adapter.out.persistence.MultiEventCandidateCaseSourceReader;
import com.ticket.defender_core.domain.Case;
import com.ticket.defender_core.domain.CaseSourceReference;
import com.ticket.defender_core.security.AdminAccessService;
import com.ticket.defender_core.security.AdminActor;
import com.ticket.defender_core.security.AdminRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MultiEventCaseSourceVerifierTest {
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID EVENT_A = UUID.randomUUID();
    private static final UUID EVENT_B = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();
    private static final String CANDIDATE = "multi-" + "a".repeat(64);
    private static final LocalDateTime COMPLETED_AT = LocalDateTime.parse("2026-11-20T12:00:00");
    private static final LocalDateTime LINKED_AT = LocalDateTime.parse("2026-11-21T12:00:00");
    private static final Set<AdminRole> CREATE_ROLES = Set.of(AdminRole.ANALYST, AdminRole.REVIEWER);

    @Mock private MultiEventCandidateCaseSourceReader candidates;
    @Mock private AdminAccessService access;

    private MultiEventCaseSourceVerifier verifier;
    private Case target;

    @BeforeEach
    void setUp() {
        verifier = new MultiEventCaseSourceVerifier(candidates, access);
        target = new Case(UUID.randomUUID(), TENANT, EVENT_A, UUID.randomUUID(), Case.Type.GRAPH_CLUSTER,
                null, null, LINKED_AT);
    }

    @Test
    void verifiesCompletedCandidateAndCreateAccessToEveryRunEvent() {
        when(candidates.findCompletedCandidate(TENANT, RUN, CANDIDATE)).thenReturn(Optional.of(
                new MultiEventCandidateCaseSourceReader.CompletedCandidate(COMPLETED_AT, "synthetic-exp2")));
        when(candidates.findEventIds(TENANT, RUN)).thenReturn(List.of(EVENT_A, EVENT_B));
        when(access.requireEventAccess(any(UUID.class), eq(CREATE_ROLES))).thenAnswer(call ->
                new AdminActor("synthetic-subject", TENANT, call.getArgument(0)));

        List<CaseSourceReference> result = verifier.verify(target,
                List.of(new CaseDto.MultiEventCandidate(RUN, CANDIDATE)), LINKED_AT);

        assertThat(result).singleElement().satisfies(source -> {
            assertThat(source.getSourceType()).isEqualTo(CaseSourceReference.Type.MULTI_EVENT_CANDIDATE);
            assertThat(source.getMultiEventRunId()).isEqualTo(RUN);
            assertThat(source.getMultiEventCandidateId()).isEqualTo(CANDIDATE);
            assertThat(source.getMultiEventCompletedAt()).isEqualTo(COMPLETED_AT);
            assertThat(source.getTenantId()).isEqualTo(TENANT);
            assertThat(source.getEventId()).isEqualTo(EVENT_A);
        });
        verify(access, times(2)).requireEventAccess(any(UUID.class), eq(CREATE_ROLES));
    }

    @Test
    void hidesMissingOrIncompleteCandidate() {
        when(candidates.findCompletedCandidate(TENANT, RUN, CANDIDATE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> verifier.verify(target,
                List.of(new CaseDto.MultiEventCandidate(RUN, CANDIDATE)), LINKED_AT))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("RESOURCE_NOT_FOUND"));
        verify(candidates, never()).findEventIds(any(), any());
        verify(access, never()).requireEventAccess(any(), anySet());
    }

    @Test
    void requiresTheCaseEventToBeIncludedInTheCompletedRun() {
        when(candidates.findCompletedCandidate(TENANT, RUN, CANDIDATE)).thenReturn(Optional.of(
                new MultiEventCandidateCaseSourceReader.CompletedCandidate(COMPLETED_AT, "synthetic-exp2")));
        when(candidates.findEventIds(TENANT, RUN)).thenReturn(List.of(EVENT_B));

        assertThatThrownBy(() -> verifier.verify(target,
                List.of(new CaseDto.MultiEventCandidate(RUN, CANDIDATE)), LINKED_AT))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("RESOURCE_NOT_FOUND"));
        verify(access, never()).requireEventAccess(any(), anySet());
    }

    @Test
    void rejectsAccessToAnyEventOutsideTheCaseTenant() {
        when(candidates.findCompletedCandidate(TENANT, RUN, CANDIDATE)).thenReturn(Optional.of(
                new MultiEventCandidateCaseSourceReader.CompletedCandidate(COMPLETED_AT, "synthetic-exp2")));
        when(candidates.findEventIds(TENANT, RUN)).thenReturn(List.of(EVENT_A, EVENT_B));
        when(access.requireEventAccess(any(UUID.class), eq(CREATE_ROLES))).thenAnswer(call ->
                new AdminActor("synthetic-subject", EVENT_B.equals(call.getArgument(0)) ? OTHER_TENANT : TENANT,
                        call.getArgument(0)));

        assertThatThrownBy(() -> verifier.verify(target,
                List.of(new CaseDto.MultiEventCandidate(RUN, CANDIDATE)), LINKED_AT))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("RESOURCE_NOT_FOUND"));
    }

    @Test
    void rechecksCandidateAndEveryEventBeforeReturningItsSource() {
        when(candidates.findCompletedCandidate(TENANT, RUN, CANDIDATE)).thenReturn(Optional.of(
                new MultiEventCandidateCaseSourceReader.CompletedCandidate(COMPLETED_AT, "synthetic-exp2")));
        when(candidates.findEventIds(TENANT, RUN)).thenReturn(List.of(EVENT_A, EVENT_B));
        when(access.requireEventAccess(any(UUID.class), eq(Set.of(AdminRole.TENANT_ADMIN,
                AdminRole.ANALYST, AdminRole.REVIEWER)))).thenAnswer(call ->
                new AdminActor("synthetic-subject", TENANT, call.getArgument(0)));

        verifier.requireReadable(target, RUN, CANDIDATE);

        verify(access, times(2)).requireEventAccess(any(UUID.class),
                eq(Set.of(AdminRole.TENANT_ADMIN, AdminRole.ANALYST, AdminRole.REVIEWER)));
    }

    @Test
    void rejectsNonCanonicalCandidateIdentifiersBeforeLookup() {
        assertThatThrownBy(() -> verifier.verify(target,
                List.of(new CaseDto.MultiEventCandidate(RUN, "account-reference")), LINKED_AT))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("VALIDATION_FAILED"));
        verify(candidates, never()).findCompletedCandidate(any(), any(), any());
    }
}
