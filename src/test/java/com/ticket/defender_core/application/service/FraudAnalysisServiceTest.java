package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.AgentAnalysisRequest;
import com.ticket.defender_core.adapter.out.api.FastApiAdapter;
import com.ticket.defender_core.adapter.out.api.dto.FastApiClusterResponse;
import com.ticket.defender_core.adapter.out.persistence.TicketAuditRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

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

    @Test
    @DisplayName("FastAPI 분석 결과가 정상일 때, 중복이 없다면 DB에 잘 적재되어야 한다.")
    void processAgentData_Success() {
        AgentAnalysisRequest request = new AgentAnalysisRequest(List.of());

        FastApiClusterResponse.ClusterData fakeCluster = new FastApiClusterResponse.ClusterData(
                "hash1234", 2, List.of("userA", "userB")
        );
        FastApiClusterResponse fakeResponse = new FastApiClusterResponse(
                "success", "완료", List.of(fakeCluster)
        );

        given(fastApiAdapter.requestFraudAnalysis(request)).willReturn(fakeResponse);
        given(ticketAuditRepository.findExistingPaymentHashes(anyList())).willReturn(Collections.emptySet());

        fraudAnalysisService.processAgentData(request);

        verify(ticketAuditRepository, times(1)).saveAll(anyList());
    }

    @Test
    @DisplayName("FastAPI 서버가 다운되어 Fallback(빈 결과)이 오면, DB에 아무것도 저장하지 않는다.")
    void processAgentData_Fallback_NoSave() {
        AgentAnalysisRequest request = new AgentAnalysisRequest(List.of());
        FastApiClusterResponse fallbackResponse = new FastApiClusterResponse(
                "fallback", "장애", Collections.emptyList()
        );

        given(fastApiAdapter.requestFraudAnalysis(request)).willReturn(fallbackResponse);

        fraudAnalysisService.processAgentData(request);

        verify(ticketAuditRepository, times(0)).saveAll(any());
    }
}