package com.ticket.defender_core.adapter.in.web;

import com.ticket.defender_core.adapter.in.web.dto.AdminOperationSettingsDto;
import com.ticket.defender_core.application.service.AdminOperationSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/operations")
@RequiredArgsConstructor
public class AdminOperationSettingsController {
    private final AdminOperationSettingsService service;

    @GetMapping("/settings")
    public ResponseEntity<AdminOperationSettingsDto> get(@RequestParam UUID eventId) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.get(eventId));
    }
}
