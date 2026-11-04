package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.CandidatePipelineDto;
import com.ticket.defender_core.adapter.out.persistence.FanReportPerformanceLinkRepository;
import com.ticket.defender_core.adapter.out.persistence.PerformanceLinkAuditRepository;
import com.ticket.defender_core.adapter.out.persistence.VerifiedPerformanceRepository;
import com.ticket.defender_core.domain.FanReportPerformanceLink;
import com.ticket.defender_core.domain.VerifiedPerformance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PerformanceLinkServiceTest {
    private final UUID tenantId = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private final UUID eventId = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final UUID memberId = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private final UUID reportId = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private final UUID performanceId = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private CandidatePipelineAccess access;
    private FanReportPerformanceLinkRepository links;
    private PerformanceLinkAuditRepository audits;
    private VerifiedPerformanceRepository performances;
    private PerformanceLinkService service;

    @BeforeEach
    void setup() {
        access = mock(CandidatePipelineAccess.class);
        links = mock(FanReportPerformanceLinkRepository.class);
        audits = mock(PerformanceLinkAuditRepository.class);
        performances = mock(VerifiedPerformanceRepository.class);
        service = new PerformanceLinkService(access, links, audits, performances);
        when(access.report(eq(reportId), any())).thenReturn(new CandidatePipelineAccess.Scope(tenantId, eventId, memberId, null));
        when(audits.findByActorMembershipIdAndFanReportIdAndIdempotencyKeyDigest(eq(memberId), eq(reportId), any()))
                .thenReturn(Optional.empty());
        when(links.lockActiveByFanReportId(reportId)).thenReturn(Optional.empty());
        VerifiedPerformance performance = new VerifiedPerformance(performanceId, tenantId, eventId, "round-1",
                "verified by synthetic test", "v1", memberId, LocalDateTime.now());
        when(performances.lockByIdAndScope(performanceId, tenantId, eventId)).thenReturn(Optional.of(performance));
    }

    @Test
    void explicitLinkCreatesVersionedActiveLinkWithoutMutatingFanInputPerformance() {
        CandidatePipelineDto.PerformanceLink result = service.link(reportId, UUID.randomUUID().toString(),
                new CandidatePipelineDto.LinkPerformance(performanceId, 0, "operator-confirmed"));

        assertThat(result.status()).isEqualTo("ACTIVE");
        assertThat(result.linkRevision()).isEqualTo(1);
        verify(links).saveAndFlush(any(FanReportPerformanceLink.class));
        verify(audits).saveAndFlush(any());
    }

    @Test
    void changedExpectedLinkRevisionIsRejectedBeforeReplacingActiveLink() {
        FanReportPerformanceLink active = mock(FanReportPerformanceLink.class);
        when(active.getLinkRevision()).thenReturn(2);
        when(links.lockActiveByFanReportId(reportId)).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> service.link(reportId, UUID.randomUUID().toString(),
                new CandidatePipelineDto.LinkPerformance(performanceId, 1, "operator-confirmed")))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("PERFORMANCE_LINK_REVISION_CONFLICT"));
    }

    @Test
    void replacementClearsTheUniqueActiveSlotBeforeAddingTheNextHistoricalLink() {
        FanReportPerformanceLink active = mock(FanReportPerformanceLink.class);
        when(active.getLinkRevision()).thenReturn(1);
        when(active.getPerformanceId()).thenReturn(UUID.randomUUID());
        when(active.getPerformanceRevision()).thenReturn(1);
        when(links.lockActiveByFanReportId(reportId)).thenReturn(Optional.of(active));

        CandidatePipelineDto.PerformanceLink result = service.link(reportId, UUID.randomUUID().toString(),
                new CandidatePipelineDto.LinkPerformance(performanceId, 1, "operator replacement"));

        assertThat(result.status()).isEqualTo("ACTIVE");
        var order = inOrder(links);
        order.verify(links).saveAndFlush(active);
        order.verify(links).saveAndFlush(org.mockito.ArgumentMatchers.any(FanReportPerformanceLink.class));
    }
}
