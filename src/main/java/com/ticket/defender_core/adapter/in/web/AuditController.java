package com.ticket.defender_core.adapter.in.web;

import com.ticket.defender_core.adapter.in.web.dto.AgentAnalysisRequest;
import com.ticket.defender_core.application.service.FraudAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/audits")
@RequiredArgsConstructor
public class AuditController {

    private final FraudAnalysisService fraudAnalysisService;

    @PostMapping("/analyze")
    public ResponseEntity<String> receiveAndAnalyze(@RequestBody AgentAnalysisRequest request) {
        if (request.tickets() == null || request.tickets().isEmpty()) {
            return ResponseEntity.badRequest().body("요청 데이터가 비어있습니다.");
        }

        fraudAnalysisService.processAgentData(request);

        return ResponseEntity.ok("데이터 수신 및 분석 파이프라인 처리가 완료되었습니다.");
    }
}
