package com.ticket.defender_core.adapter.in.web;

import com.ticket.defender_core.adapter.in.web.dto.ApprovalRequestDto;
import com.ticket.defender_core.application.service.ApprovalRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class ApprovalRequestController {
    private final ApprovalRequestService service;

    @PostMapping("/api/v1/cases/{caseId}/approval-requests")
    public ResponseEntity<ApprovalRequestDto.View> submit(@PathVariable UUID caseId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody ApprovalRequestDto.Submit input) {
        return ResponseEntity.status(HttpStatus.CREATED).header("Cache-Control", "no-store")
                .body(service.submit(caseId, input, key));
    }

    @GetMapping("/api/v1/approval-requests/{approvalRequestId}")
    public ResponseEntity<ApprovalRequestDto.View> detail(@PathVariable UUID approvalRequestId) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(service.detail(approvalRequestId));
    }
}
