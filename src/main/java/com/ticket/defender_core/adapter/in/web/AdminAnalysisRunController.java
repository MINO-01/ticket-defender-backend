package com.ticket.defender_core.adapter.in.web;

import com.ticket.defender_core.adapter.in.web.dto.AdminAnalysisRunDto;
import com.ticket.defender_core.application.service.AdminAnalysisRunQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/analysis-runs")
@RequiredArgsConstructor
public class AdminAnalysisRunController {
    private final AdminAnalysisRunQueryService service;

    @GetMapping
    public ResponseEntity<AdminAnalysisRunDto.Page> list(@RequestParam UUID eventId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.list(eventId, page, pageSize));
    }
}
