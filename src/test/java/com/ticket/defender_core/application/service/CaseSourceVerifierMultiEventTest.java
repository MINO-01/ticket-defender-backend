package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.CaseDto;
import com.ticket.defender_core.adapter.out.persistence.EvidenceAssetRepository;
import com.ticket.defender_core.adapter.out.persistence.FanReportRepository;
import com.ticket.defender_core.adapter.out.persistence.ListingCandidateMatchRunRepository;
import com.ticket.defender_core.adapter.out.persistence.ListingContextAnalysisRepository;
import com.ticket.defender_core.adapter.out.persistence.MacroAuditEvidenceReadRepository;
import com.ticket.defender_core.domain.Case;
import com.ticket.defender_core.domain.CaseSourceReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CaseSourceVerifierMultiEventTest {
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID EVENT = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();
    private static final String CANDIDATE = "multi-" + "b".repeat(64);
    private static final LocalDateTime NOW = LocalDateTime.parse("2026-11-22T12:00:00");

    @Mock private MacroAuditEvidenceReadRepository clusters;
    @Mock private FanReportRepository reports;
    @Mock private EvidenceAssetRepository assets;
    @Mock private ListingCandidateMatchRunRepository candidateRuns;
    @Mock private ListingContextAnalysisRepository analyses;
    @Mock private CandidateRunApplicability candidateApplicability;
    @Mock private EvidenceObjectStorage storage;
    @Mock private MultiEventCaseSourceVerifier multiEventCandidates;

    private CaseSourceVerifier verifier;
    private Case target;

    @BeforeEach
    void setUp() {
        verifier = new CaseSourceVerifier(clusters, reports, assets, candidateRuns, analyses,
                candidateApplicability, storage, multiEventCandidates);
        target = new Case(UUID.randomUUID(), TENANT, EVENT, UUID.randomUUID(), Case.Type.GRAPH_CLUSTER,
                null, null, NOW);
    }

    @Test
    void graphCaseCanContainOnlyAServerVerifiedMultiEventCandidate() {
        CaseDto.MultiEventCandidate inputSource = new CaseDto.MultiEventCandidate(RUN, CANDIDATE);
        CaseSourceReference reference = CaseSourceReference.multiEventCandidate(target, "c".repeat(64), RUN,
                CANDIDATE, NOW.minusMinutes(1), NOW);
        when(multiEventCandidates.verify(target, List.of(inputSource), NOW)).thenReturn(List.of(reference));
        CaseDto.Create input = new CaseDto.Create(EVENT, Case.Type.GRAPH_CLUSTER, List.of(), List.of(), List.of(),
                List.of(), List.of(inputSource), "Investigate completed multi-event candidate", null, null);

        List<CaseSourceReference> result = verifier.verify(target, input, NOW);

        assertThat(result).singleElement().satisfies(source -> {
            assertThat(source.getSourceType()).isEqualTo(CaseSourceReference.Type.MULTI_EVENT_CANDIDATE);
            assertThat(source.getMultiEventRunId()).isEqualTo(RUN);
            assertThat(source.getMultiEventCandidateId()).isEqualTo(CANDIDATE);
        });
    }

    @Test
    void doesNotTreatMultiEventCandidateAsAFanReportSource() {
        CaseDto.Create input = new CaseDto.Create(EVENT, Case.Type.FAN_REPORT, List.of(), List.of(), List.of(),
                List.of(), List.of(new CaseDto.MultiEventCandidate(RUN, CANDIDATE)),
                "Invalid source type", null, null);

        assertThatThrownBy(() -> verifier.verify(target, input, NOW))
                .isInstanceOf(CaseApiException.class);
        verify(multiEventCandidates, never()).verify(target,
                List.of(new CaseDto.MultiEventCandidate(RUN, CANDIDATE)), NOW);
    }
}
