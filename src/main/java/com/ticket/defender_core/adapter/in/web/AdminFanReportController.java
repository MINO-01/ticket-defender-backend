package com.ticket.defender_core.adapter.in.web;

import com.ticket.defender_core.adapter.in.web.dto.AdminFanReportDto;
import com.ticket.defender_core.application.service.AdminFanReportQueryService;
import com.ticket.defender_core.domain.FanReport;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 관리자 팬 제보 조회 API입니다. 관리자 JWT와 서버 저장 tenant/event 범위로만 접근합니다. */
@RestController
@RequestMapping("/api/v1/admin/fan-reports")
@RequiredArgsConstructor
public class AdminFanReportController {
    private final AdminFanReportQueryService service;

    @GetMapping
    public ResponseEntity<AdminFanReportDto.Page> list(@RequestParam UUID eventId,
            @RequestParam(required = false) FanReport.IntakeStatus intakeStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(service.list(eventId, intakeStatus, page, pageSize));
    }

    @GetMapping("/{fanReportId}")
    public ResponseEntity<AdminFanReportDto.Detail> detail(@PathVariable UUID fanReportId) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.detail(fanReportId));
    }
}
