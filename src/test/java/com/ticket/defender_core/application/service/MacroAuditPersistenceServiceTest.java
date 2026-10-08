package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.api.dto.FastApiClusterResponse;
import com.ticket.defender_core.adapter.out.api.dto.MacroAnalysisRequest;
import com.ticket.defender_core.adapter.out.api.dto.MacroAnalysisResponse;
import com.ticket.defender_core.adapter.out.persistence.TicketAuditRepository;
import com.ticket.defender_core.domain.AuditStatus;
import com.ticket.defender_core.domain.EvidenceType;
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

    @InjectMocks
    private MacroAuditPersistenceService persistenceService;

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
        given(ticketAuditRepository.findAllByEventIdInAndReservationNoIn(
                List.of("event-1"), List.of("res-1", "res-2"))).willReturn(List.of());
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
        assertEquals("event-1", firstAudit.getEventId());
        assertEquals(DEVICE_HASH, firstAudit.getDeviceIdHash());
        assertEquals(IP_HASH, savedAudits.get().get(1).getIpHash());
        assertEquals("cluster-001", firstAudit.getClusterId());
        assertEquals(0.91, firstAudit.getRiskScore());
        assertEquals("LOUVAIN", firstAudit.getAnalysisAlgorithm());
        assertEquals("1.0.0", firstAudit.getAnalysisAlgorithmVersion());
        assertEquals(REQUEST_ID, firstAudit.getAnalysisRequestId());
        assertEquals(ANALYZED_AT, firstAudit.getAnalysisCompletedAt());
        assertEquals(EvidenceType.MACRO_GRAPH, firstAudit.getEvidenceType());
        assertEquals(AuditStatus.FRAUD_DETECTED, firstAudit.getStatus());
    }

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

    private MacroAnalysisRequest request() {
        return new MacroAnalysisRequest(REQUEST_ID, List.of(
                new MacroAnalysisRequest.Ticket(
                        "uid-1", "res-1", "event-1", PAYMENT_HASH, null, DEVICE_HASH, null),
                new MacroAnalysisRequest.Ticket(
                        "uid-2", "res-2", "event-1", PAYMENT_HASH, ADDRESS_HASH, null, IP_HASH)
        ));
    }

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
