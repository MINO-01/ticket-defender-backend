package com.ticket.defender_core.adapter.in.web;

import com.ticket.defender_core.adapter.in.web.dto.AgentAnalysisRequest;
import com.ticket.defender_core.application.service.FraudAnalysisStatus;
import com.ticket.defender_core.application.service.FraudAnalysisService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/audits")
@RequiredArgsConstructor
public class AuditController {

    private final FraudAnalysisService fraudAnalysisService;

    @PostMapping("/analyze")
    public ResponseEntity<String> receiveAndAnalyze(@Valid @RequestBody AgentAnalysisRequest request) {
        FraudAnalysisStatus status = fraudAnalysisService.processAgentData(request);
        if (status == FraudAnalysisStatus.UNAVAILABLE) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("분석 서버가 응답하지 않아 요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }

        return ResponseEntity.ok("데이터 수신 및 분석 파이프라인 처리가 완료되었습니다.");
    }
}
