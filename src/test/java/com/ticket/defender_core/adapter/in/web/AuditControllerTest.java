package com.ticket.defender_core.adapter.in.web;

import com.ticket.defender_core.adapter.in.web.dto.AgentAnalysisRequest;
import com.ticket.defender_core.application.service.FraudAnalysisService;
import com.ticket.defender_core.application.service.FraudAnalysisStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class AuditControllerTest {

    @Mock
    private FraudAnalysisService fraudAnalysisService;

    @InjectMocks
    private AuditController auditController;

    /** 분석 서버 장애는 HTTP 503으로 응답합니다. */
    @Test
    @DisplayName("분석 서버가 응답하지 않으면 503 상태를 반환한다")
    void receiveAndAnalyze_whenAnalysisUnavailable_returnsServiceUnavailable() {
        // given
        AgentAnalysisRequest request = new AgentAnalysisRequest(List.of(
                new AgentAnalysisRequest.TicketData(
                        "uid-1", "res-1", "event-1", "a".repeat(64), null, null, null)
        ));
        given(fraudAnalysisService.processAgentData(request)).willReturn(FraudAnalysisStatus.UNAVAILABLE);

        // when
        ResponseEntity<String> response = auditController.receiveAndAnalyze(request);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).contains("분석 서버가 응답하지 않아");
    }

    /** 분석이 완료되면 HTTP 200으로 응답합니다. */
    @Test
    @DisplayName("분석이 정상 완료되면 200 상태를 반환한다")
    void receiveAndAnalyze_whenCompleted_returnsOk() {
        // given
        AgentAnalysisRequest request = new AgentAnalysisRequest(List.of(
                new AgentAnalysisRequest.TicketData(
                        "uid-1", "res-1", "event-1", "a".repeat(64), null, null, null)
        ));
        given(fraudAnalysisService.processAgentData(request)).willReturn(FraudAnalysisStatus.COMPLETED);

        // when
        ResponseEntity<String> response = auditController.receiveAndAnalyze(request);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("처리가 완료되었습니다");
    }
}
