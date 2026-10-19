package com.ticket.defender_core.adapter.in.web;

import com.ticket.defender_core.adapter.in.web.dto.Track1ClusterDto;
import com.ticket.defender_core.application.service.AdminTrack1ClusterService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/track1")
@RequiredArgsConstructor
public class AdminTrack1ClusterController {
    private final AdminTrack1ClusterService clusters;

    @GetMapping("/clusters")
    public Track1ClusterDto.Page list(@RequestParam UUID eventId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "priority-desc") String sort) {
        return clusters.list(eventId, page, pageSize, sort);
    }

    @GetMapping("/clusters/detail")
    public Track1ClusterDto.Detail detail(@RequestParam UUID eventId,
            @RequestParam String analysisRequestId, @RequestParam String clusterId) {
        return clusters.detail(eventId, analysisRequestId, clusterId);
    }
}
