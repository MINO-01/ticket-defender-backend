package com.ticket.defender_core.adapter.in.web;

import com.ticket.defender_core.adapter.in.web.dto.AdminEventDto;
import com.ticket.defender_core.application.service.AdminEventQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 접근 가능 공연 목록 API입니다. tenant는 요청 값이 아니라 서버 멤버십으로 결정합니다. */
@RestController
@RequestMapping("/api/v1/admin/events")
@RequiredArgsConstructor
public class AdminEventController {
    private final AdminEventQueryService service;

    @GetMapping
    public ResponseEntity<AdminEventDto.Page> list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.list(page, pageSize));
    }
}
