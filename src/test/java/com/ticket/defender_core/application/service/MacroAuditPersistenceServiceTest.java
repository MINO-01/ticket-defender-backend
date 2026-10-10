package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.api.dto.FastApiClusterResponse;
import com.ticket.defender_core.adapter.out.api.dto.MacroAnalysisRequest;
import com.ticket.defender_core.adapter.out.api.dto.MacroAnalysisResponse;
import com.ticket.defender_core.adapter.out.persistence.MacroAuditEvidenceRepository;
import com.ticket.defender_core.adapter.out.persistence.TicketAuditRepository;
import com.ticket.defender_core.domain.AuditStatus;
import com.ticket.defender_core.domain.EvidenceType;
import com.ticket.defender_core.domain.MacroAnalysisEvidence;
import com.ticket.defender_core.domain.MacroAuditEvidence;
import com.ticket.defender_core.domain.TicketAudit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MacroAuditPersistenceServiceTest {

    private static final String PAYMENT_HASH = "a".repeat(64);
    private static final String ADDRESS_HASH = "b".repeat(64);
    private static final String DEVICE_HASH = "c".repeat(64);
    private static final String IP_HASH = "d".repeat(64);
    private static final String REQUEST_ID = "2136558c-2326-4430-a46d-0793494e9b49";
    private static final LocalDateTime ANALYZED_AT = LocalDateTime.parse("2026-10-08T10:15:30");

    @Mock
    private TicketAuditRepository ticketAuditRepository;

    @Mock
    private MacroAuditEvidenceRepository macroAuditEvidenceRepository;

    @InjectMocks
    private MacroAuditPersistenceService persistenceService;

    /** 군집 구성원과 분석 출처를 매크로 증거로 저장합니다. */
    @Test
    @DisplayName("군집 구성원별 티켓 정보와 분석 출처를 감사 내역으로 저장한다")
    void persistDetectedAudits_mapsClusterMembersAndProvenance() {
        // given
        MacroAnalysisRequest request = request();
        MacroAnalysisResponse response = response(
                new FastApiClusterResponse.Member("uid-1", "res-1", "event-1"),
                new FastApiClusterResponse.Member("uid-2", "res-2", "event-1")
        );
        AtomicReference<List<TicketAudit>> savedAudits = new AtomicReference<>();
        given(macroAuditEvidenceRepository.findAllByEvidenceKeyIn(any()))
                .willReturn(List.of());
        given(ticketAuditRepository.saveAll(any())).willAnswer(invocation -> {
            Iterable<TicketAudit> values = invocation.getArgument(0);
            List<TicketAudit> copy = new ArrayList<>();
            values.forEach(copy::add);
            savedAudits.set(copy);
            return copy;
        });

        // when
        persistenceService.persistDetectedAudits(response, request);

        // then
        verify(ticketAuditRepository).saveAll(any());
        assertEquals(2, savedAudits.get().size());
        TicketAudit firstAudit = savedAudits.get().get(0);
        assertEquals("uid-1", firstAudit.getAccountId());
        assertEquals("res-1", firstAudit.getReservationNo());
        MacroAuditEvidence firstEvidence = firstAudit.getMacroAuditEvidence();
        assertEquals("event-1", firstEvidence.getEventId());
        assertEquals(DEVICE_HASH, firstEvidence.getDeviceIdHash());
        assertEquals(IP_HASH, savedAudits.get().get(1).getMacroAuditEvidence().getIpHash());
        assertEquals("cluster-001", firstEvidence.getClusterId());
        assertEquals(0.91, firstEvidence.getRiskScore());
        assertEquals("LOUVAIN", firstEvidence.getAnalysisAlgorithm());
        assertEquals("1.0.0", firstEvidence.getAnalysisAlgorithmVersion());
        assertEquals(REQUEST_ID, firstEvidence.getAnalysisRequestId());
        assertEquals(ANALYZED_AT, firstEvidence.getAnalysisCompletedAt());
        assertEquals(64, firstEvidence.getEvidenceKey().length());
        assertEquals(EvidenceType.MACRO_GRAPH, firstAudit.getEvidenceType());
        assertEquals(AuditStatus.FRAUD_DETECTED, firstAudit.getStatus());
    }

    /** 요청에 없는 군집 구성원은 저장하지 않습니다. */
    @Test
    @DisplayName("요청에 포함되지 않은 예매 내역이 응답에 있으면 저장하지 않고 실패한다")
    void persistDetectedAudits_rejectsUnknownMember() {
        // given
        MacroAnalysisRequest request = request();
        MacroAnalysisResponse response = response(
                new FastApiClusterResponse.Member("uid-not-requested", "res-1", "event-1"),
                new FastApiClusterResponse.Member("uid-2", "res-2", "event-1")
        );

        // when / then
        assertThrows(IllegalStateException.class,
                () -> persistenceService.persistDetectedAudits(response, request));
        verify(ticketAuditRepository, never()).saveAll(any());
    }

    /** 이미 저장된 식별 조합은 다시 만들지 않습니다. */
    @Test
    @DisplayName("전용 매크로 증거 테이블에 이미 저장된 예매 건은 다시 저장하지 않는다")
    void persistDetectedAudits_skipsPreviouslyPersistedMacroEvidence() {
        // given
        MacroAnalysisRequest request = request();
        MacroAnalysisResponse response = response(
                new FastApiClusterResponse.Member("uid-1", "res-1", "event-1"),
                new FastApiClusterResponse.Member("uid-2", "res-2", "event-1")
        );
        MacroAnalysisEvidence existingAnalysis = new MacroAnalysisEvidence(
                "uid-1", "res-1", "event-1", PAYMENT_HASH, null, DEVICE_HASH, null,
                "cluster-001", 0.91, "LOUVAIN", "1.0.0", REQUEST_ID, ANALYZED_AT);
        TicketAudit existingAudit = TicketAudit.createMacroAudit(existingAnalysis);
        MacroAuditEvidence existingEvidence = existingAudit.getMacroAuditEvidence();
        given(macroAuditEvidenceRepository.findAllByEvidenceKeyIn(any()))
                .willReturn(List.of(existingEvidence));
        AtomicReference<List<TicketAudit>> savedAudits = new AtomicReference<>();
        given(ticketAuditRepository.saveAll(any())).willAnswer(invocation -> {
            Iterable<TicketAudit> values = invocation.getArgument(0);
            List<TicketAudit> copy = new ArrayList<>();
            values.forEach(copy::add);
            savedAudits.set(copy);
            return copy;
        });

        // when
        persistenceService.persistDetectedAudits(response, request);

        // then
        verify(ticketAuditRepository).saveAll(any());
        assertEquals(1, savedAudits.get().size());
        assertEquals("uid-2", savedAudits.get().getFirst().getAccountId());
    }

    /** 테스트용 예매 두 건을 요청에 담습니다. */
    private MacroAnalysisRequest request() {
        return new MacroAnalysisRequest(REQUEST_ID, List.of(
                new MacroAnalysisRequest.Ticket(
                        "uid-1", "res-1", "event-1", PAYMENT_HASH, null, DEVICE_HASH, null),
                new MacroAnalysisRequest.Ticket(
                        "uid-2", "res-2", "event-1", PAYMENT_HASH, ADDRESS_HASH, null, IP_HASH)
        ));
    }

    /** 요청 구성원으로 군집 응답을 만듭니다. */
    private MacroAnalysisResponse response(FastApiClusterResponse.Member... members) {
        FastApiClusterResponse cluster = new FastApiClusterResponse(
                "cluster-001",
                List.of(members),
                List.of(PAYMENT_HASH),
                List.of(ADDRESS_HASH),
                List.of(DEVICE_HASH),
                List.of(IP_HASH),
                0.91
        );
        return new MacroAnalysisResponse(
                REQUEST_ID,
                MacroAnalysisResponse.Status.COMPLETED,
                "LOUVAIN",
                "1.0.0",
                OffsetDateTime.parse("2026-10-08T10:15:30Z"),
                List.of(cluster)
        );
    }
}
