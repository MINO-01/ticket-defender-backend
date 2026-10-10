package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.AgentAnalysisRequest;
import com.ticket.defender_core.adapter.out.api.FastApiAdapter;
import com.ticket.defender_core.adapter.out.api.dto.MacroAnalysisRequest;
import com.ticket.defender_core.adapter.out.api.dto.MacroAnalysisResponse;
import org.springframework.dao.DataIntegrityViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class FraudAnalysisService {

    private final FastApiAdapter fastApiAdapter;
    private final MacroAuditPersistenceService macroAuditPersistenceService;

    /**
     * 요청 데이터를 분석 서버로 보내고 완료된 결과만 저장합니다.
     * 저장 중 유니크 키 충돌이 나면 기존 내역을 다시 조회합니다.
     */
    public FraudAnalysisStatus processAgentData(AgentAnalysisRequest request) {
        String requestId = UUID.randomUUID().toString();
        List<MacroAnalysisRequest.Ticket> tickets = request.tickets().stream()
                .map(ticket -> new MacroAnalysisRequest.Ticket(
                        ticket.accountId(),
                        ticket.reservationNo(),
                        ticket.eventId(),
                        ticket.paymentHash(),
                        ticket.addressHash(),
                        ticket.deviceIdHash(),
                        ticket.ipHash()
                ))
                .toList();
        MacroAnalysisRequest analysisRequest = new MacroAnalysisRequest(requestId, tickets);

        MacroAnalysisResponse response = fastApiAdapter.requestMacroAnalysis(analysisRequest);
        if (response == null || response.status() != MacroAnalysisResponse.Status.COMPLETED) {
            log.warn("그래프 분석을 완료하지 못해 결과를 저장하지 않습니다. 요청 ID: {}", requestId);
            return FraudAnalysisStatus.UNAVAILABLE;
        }
        if (!requestId.equals(response.requestId())) {
            throw new IllegalStateException("분석 응답의 요청 ID가 현재 요청과 일치하지 않습니다.");
        }
        if (response.clusters() == null) {
            throw new IllegalStateException("완료된 분석 응답에 군집 목록이 없습니다.");
        }
        if (response.clusters().isEmpty()) {
            log.info("그래프 분석이 완료됐으며 조사 대상 군집이 없습니다. 요청 ID: {}", requestId);
            return FraudAnalysisStatus.COMPLETED;
        }

        try {
            macroAuditPersistenceService.persistDetectedAudits(response, analysisRequest);
        } catch (DataIntegrityViolationException concurrentInsert) {
            log.info("동시 분석 요청이 같은 매크로 증거를 저장했습니다. 기존 증거를 다시 확인합니다. 요청 ID: {}",
                    requestId);
            macroAuditPersistenceService.persistDetectedAudits(response, analysisRequest);
        }
        return FraudAnalysisStatus.COMPLETED;
    }
}
