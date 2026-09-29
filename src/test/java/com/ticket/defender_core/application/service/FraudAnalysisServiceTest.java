package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.AgentAnalysisRequest;
import com.ticket.defender_core.adapter.out.api.FastApiAdapter;
import com.ticket.defender_core.adapter.out.api.dto.FastApiClusterResponse;
import com.ticket.defender_core.adapter.out.api.dto.MacroAnalysisRequest;
import com.ticket.defender_core.adapter.out.persistence.TicketAuditRepository;
import com.ticket.defender_core.domain.AuditStatus;
import com.ticket.defender_core.domain.TicketAudit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class FraudAnalysisServiceTest {

    @Mock
    private FastApiAdapter fastApiAdapter;

    @Mock
    private TicketAuditRepository ticketAuditRepository;

    @InjectMocks
    private FraudAnalysisService fraudAnalysisService;

    @Captor
    private ArgumentCaptor<List<TicketAudit>> auditListCaptor;

    @Test
    @DisplayName("FastAPI 분석 결과가 정상일 때, 중복이 없다면 실제 주소와 함께 DB에 적재되어야 한다.")
    void processAgentData_Success() {
        // given
        AgentAnalysisRequest request = new AgentAnalysisRequest(List.of(
                new AgentAnalysisRequest.TicketHashData("userA", "hash1234", "addrA"),
                new AgentAnalysisRequest.TicketHashData("userB", "hash1234", "addrB")
        ));

        FastApiClusterResponse fakeResponse = new FastApiClusterResponse(
                "cluster_1",
                List.of("hash1234"),         // 불량 결제수단 해시 목록
                List.of("addrA", "addrB"),   // 불량 주소 해시 목록
                0.99                         // 매크로 확률
        );

        given(fastApiAdapter.requestMacroAnalysis(any(MacroAnalysisRequest.class)))
                .willReturn(List.of(fakeResponse));

        given(ticketAuditRepository.findByPaymentHashIn(anyList())).willReturn(Collections.emptyList());

        // when
        fraudAnalysisService.processAgentData(request);

        // then
        verify(ticketAuditRepository, times(1)).saveAll(auditListCaptor.capture());

        List<TicketAudit> savedAudits = auditListCaptor.getValue();
        assertEquals(2, savedAudits.size());
        assertEquals("addrA", savedAudits.get(0).getAddressHash());
        assertEquals(AuditStatus.FRAUD_DETECTED, savedAudits.get(0).getStatus());
    }

    @Test
    @DisplayName("FastAPI 서버가 다운되어 빈 리스트이 오면, DB에 아무것도 저장하지 않는다.")
    void processAgentData_Fallback_NoSave() {
        // given
        AgentAnalysisRequest request = new AgentAnalysisRequest(List.of());

        given(fastApiAdapter.requestMacroAnalysis(any(MacroAnalysisRequest.class)))
                .willReturn(Collections.emptyList());

        // when
        fraudAnalysisService.processAgentData(request);

        // then
        verify(ticketAuditRepository, times(0)).saveAll(any());
    }
}