package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.AgentAnalysisRequest;
import com.ticket.defender_core.adapter.out.api.FastApiAdapter;
import com.ticket.defender_core.adapter.out.api.dto.FastApiClusterResponse;
import com.ticket.defender_core.adapter.out.api.dto.MacroAnalysisRequest;
import com.ticket.defender_core.adapter.out.api.dto.MacroAnalysisResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
class FraudAnalysisServiceTest {

    private static final String PAYMENT_HASH = "a".repeat(64);
    private static final String ADDRESS_HASH = "b".repeat(64);
    private static final String DEVICE_HASH = "c".repeat(64);
    private static final String IP_HASH = "d".repeat(64);

    @Mock
    private FastApiAdapter fastApiAdapter;

    @Mock
    private MacroAuditPersistenceService macroAuditPersistenceService;

    @InjectMocks
    private FraudAnalysisService fraudAnalysisService;

    @Captor
    private ArgumentCaptor<MacroAnalysisRequest> requestCaptor;

    @Captor
    private ArgumentCaptor<MacroAnalysisResponse> responseCaptor;

    /** 분석 결과의 군집 구성원과 출처를 저장 서비스에 전달합니다. */
    @Test
    @DisplayName("분석 요청에 티켓별 연결 정보를 보존하고 완료된 군집을 저장 서비스에 전달한다")
    void processAgentData_completedAnalysis_persistsResponse() {
        // given
        AgentAnalysisRequest input = new AgentAnalysisRequest(List.of(
                new AgentAnalysisRequest.TicketData(
                        "uid-1", "res-1", "event-1", PAYMENT_HASH, null, DEVICE_HASH, null),
                new AgentAnalysisRequest.TicketData(
                        "uid-2", "res-2", "event-1", PAYMENT_HASH, ADDRESS_HASH, null, IP_HASH)
        ));
        AtomicReference<MacroAnalysisResponse> expectedResponse = new AtomicReference<>();
        given(fastApiAdapter.requestMacroAnalysis(any(MacroAnalysisRequest.class)))
                .willAnswer(invocation -> {
                    MacroAnalysisRequest request = invocation.getArgument(0);
                    MacroAnalysisResponse response = completedResponse(request.requestId());
                    expectedResponse.set(response);
                    return response;
                });

        // when
        FraudAnalysisStatus status = fraudAnalysisService.processAgentData(input);

        // then
        verify(fastApiAdapter).requestMacroAnalysis(requestCaptor.capture());
        MacroAnalysisRequest sentRequest = requestCaptor.getValue();
        assertNotNull(sentRequest.requestId());
        assertEquals(2, sentRequest.tickets().size());
        assertEquals("res-1", sentRequest.tickets().get(0).reservationNo());
        assertEquals("event-1", sentRequest.tickets().get(0).eventId());
        assertEquals(DEVICE_HASH, sentRequest.tickets().get(0).deviceIdHash());
        assertEquals("res-2", sentRequest.tickets().get(1).reservationNo());
        assertEquals(IP_HASH, sentRequest.tickets().get(1).ipHash());

        verify(macroAuditPersistenceService)
                .persistDetectedAudits(responseCaptor.capture(), eq(sentRequest));
        assertSame(expectedResponse.get(), responseCaptor.getValue());
        assertEquals(FraudAnalysisStatus.COMPLETED, status);
    }

    /** 유니크 충돌 뒤 저장을 다시 시도합니다. */
    @Test
    @DisplayName("동시 저장 경쟁으로 유니크 키 충돌이 나면 기존 증거를 다시 조회해 누락분을 저장한다")
    void processAgentData_retriesPersistenceAfterConcurrentUniqueKeyConflict() {
        // given
        AgentAnalysisRequest input = new AgentAnalysisRequest(List.of(
                new AgentAnalysisRequest.TicketData(
                        "uid-1", "res-1", "event-1", PAYMENT_HASH, null, DEVICE_HASH, null),
                new AgentAnalysisRequest.TicketData(
                        "uid-2", "res-2", "event-1", PAYMENT_HASH, ADDRESS_HASH, null, IP_HASH)
        ));
        given(fastApiAdapter.requestMacroAnalysis(any(MacroAnalysisRequest.class)))
                .willAnswer(invocation -> {
                    MacroAnalysisRequest request = invocation.getArgument(0);
                    return completedResponse(request.requestId());
                });
        doThrow(new DataIntegrityViolationException("동시 요청 유니크 키 충돌"))
                .doNothing()
                .when(macroAuditPersistenceService)
                .persistDetectedAudits(any(MacroAnalysisResponse.class), any(MacroAnalysisRequest.class));

        // when
        FraudAnalysisStatus status = fraudAnalysisService.processAgentData(input);

        // then
        assertEquals(FraudAnalysisStatus.COMPLETED, status);
        verify(macroAuditPersistenceService, org.mockito.Mockito.times(2))
                .persistDetectedAudits(any(MacroAnalysisResponse.class), any(MacroAnalysisRequest.class));
    }

    /** 분석을 완료하지 못하면 결과를 저장하지 않습니다. */
    @Test
    @DisplayName("분석 서버가 사용할 수 없는 상태이면 감사 내역을 저장하지 않는다")
    void processAgentData_unavailable_doesNotPersist() {
        // given
        AgentAnalysisRequest input = new AgentAnalysisRequest(List.of(
                new AgentAnalysisRequest.TicketData(
                        "uid-1", "res-1", "event-1", PAYMENT_HASH, null, null, null)
        ));
        given(fastApiAdapter.requestMacroAnalysis(any(MacroAnalysisRequest.class)))
                .willAnswer(invocation -> {
                    MacroAnalysisRequest request = invocation.getArgument(0);
                    return MacroAnalysisResponse.unavailable(request.requestId());
                });

        // when
        FraudAnalysisStatus status = fraudAnalysisService.processAgentData(input);

        // then
        verify(macroAuditPersistenceService, never()).persistDetectedAudits(any(), any());
        assertEquals(FraudAnalysisStatus.UNAVAILABLE, status);
    }

    /** 군집이 없는 완료 결과는 저장 없이 처리합니다. */
    @Test
    @DisplayName("정상 분석에서 의심 군집이 없으면 감사 내역을 저장하지 않는다")
    void processAgentData_completedWithoutClusters_doesNotPersist() {
        // given
        AgentAnalysisRequest input = new AgentAnalysisRequest(List.of(
                new AgentAnalysisRequest.TicketData(
                        "uid-1", "res-1", "event-1", PAYMENT_HASH, null, null, null)
        ));
        given(fastApiAdapter.requestMacroAnalysis(any(MacroAnalysisRequest.class)))
                .willAnswer(invocation -> {
                    MacroAnalysisRequest request = invocation.getArgument(0);
                    return new MacroAnalysisResponse(
                            request.requestId(), MacroAnalysisResponse.Status.COMPLETED,
                            "LOUVAIN", "1.0.0", OffsetDateTime.parse("2026-10-08T10:15:30Z"), List.of());
                });

        // when
        FraudAnalysisStatus status = fraudAnalysisService.processAgentData(input);

        // then
        verify(macroAuditPersistenceService, never()).persistDetectedAudits(any(), any());
        assertEquals(FraudAnalysisStatus.COMPLETED, status);
    }

    /** 테스트용 완료 응답을 만듭니다. */
    private MacroAnalysisResponse completedResponse(String requestId) {
        FastApiClusterResponse cluster = new FastApiClusterResponse(
                "cluster-1",
                List.of(
                        new FastApiClusterResponse.Member("uid-1", "res-1", "event-1"),
                        new FastApiClusterResponse.Member("uid-2", "res-2", "event-1")
                ),
                List.of(PAYMENT_HASH),
                List.of(ADDRESS_HASH),
                List.of(DEVICE_HASH),
                List.of(IP_HASH),
                0.91
        );
        return new MacroAnalysisResponse(
                requestId,
                MacroAnalysisResponse.Status.COMPLETED,
                "LOUVAIN",
                "1.0.0",
                OffsetDateTime.parse("2026-10-08T10:15:30Z"),
                List.of(cluster)
        );
    }
}
