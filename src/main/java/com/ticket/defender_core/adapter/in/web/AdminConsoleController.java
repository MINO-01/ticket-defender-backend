package com.ticket.defender_core.adapter.in.web;

import com.ticket.defender_core.adapter.in.web.dto.AdminConsoleDto;
import com.ticket.defender_core.application.service.AdminConsoleQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminConsoleController {
    private final AdminConsoleQueryService service;

    @GetMapping("/me")
    public ResponseEntity<AdminConsoleDto.Me> me() {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.me());
    }

    @GetMapping("/events/{eventId}/approval-requests")
    public ResponseEntity<AdminConsoleDto.Page<AdminConsoleDto.ApprovalRequestItem>> approvals(@PathVariable UUID eventId,
            @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.approvalRequests(eventId, status, page, pageSize));
    }

    @GetMapping("/events/{eventId}/approval-audit-events")
    public ResponseEntity<AdminConsoleDto.Page<AdminConsoleDto.AuditEventItem>> audits(@PathVariable UUID eventId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.auditEvents(eventId, page, pageSize));
    }

    @GetMapping("/events/{eventId}/workflow-summary")
    public ResponseEntity<AdminConsoleDto.Summary> summary(@PathVariable UUID eventId) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.summary(eventId));
    }
}
