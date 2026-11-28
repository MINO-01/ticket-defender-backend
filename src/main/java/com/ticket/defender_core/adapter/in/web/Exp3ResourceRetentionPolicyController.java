package com.ticket.defender_core.adapter.in.web;

import com.ticket.defender_core.adapter.in.web.dto.Exp3ResourceRetentionPolicyDto;
import com.ticket.defender_core.application.service.Exp3ResourceRetentionPolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/events/{eventId}/exp3-resource-retention-policies")
public class Exp3ResourceRetentionPolicyController {
    private final Exp3ResourceRetentionPolicyService service;

    @PostMapping
    public ResponseEntity<Exp3ResourceRetentionPolicyDto.View> approve(
            @PathVariable UUID eventId, @RequestBody Exp3ResourceRetentionPolicyDto.Create input) {
        return ResponseEntity.status(HttpStatus.CREATED).header("Cache-Control", "no-store")
                .body(service.approve(eventId, input));
    }

    @GetMapping
    public ResponseEntity<List<Exp3ResourceRetentionPolicyDto.View>> list(@PathVariable UUID eventId) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.list(eventId));
    }
}
