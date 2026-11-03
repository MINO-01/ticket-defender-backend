package com.ticket.defender_core.adapter.in.web;

import com.ticket.defender_core.adapter.in.web.dto.AdminFanReportDto;
import com.ticket.defender_core.application.service.AdminEvidenceContentService;
import com.ticket.defender_core.application.service.AdminFanReportQueryService;
import com.ticket.defender_core.domain.FanReport;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.UUID;

/** 관리자 팬 제보 조회 API입니다. 관리자 JWT와 서버 저장 tenant/event 범위로만 접근합니다. */
@RestController
@RequestMapping("/api/v1/admin/fan-reports")
@RequiredArgsConstructor
public class AdminFanReportController {
    private final AdminFanReportQueryService service;
    private final AdminEvidenceContentService content;

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

    /**
     * 검증된 원본 이미지를 Backend가 직접 전달합니다. 사전 서명 URL이나 저장소 주소는 돌려주지 않습니다.
     * 검증과 성공 감사가 끝난 뒤에만 바이트를 쓰고, 응답을 다 쓴 다음 동시 읽기 허가를 돌려줍니다.
     */
    @GetMapping("/{fanReportId}/evidence/{evidenceId}/content")
    public void evidenceContent(@PathVariable UUID fanReportId, @PathVariable UUID evidenceId,
            HttpServletResponse response) throws IOException {
        try (AdminEvidenceContentService.Content verified = content.read(fanReportId, evidenceId)) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(verified.mediaType());
            response.setContentLengthLong(verified.bytes().length);
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.inline().filename(verified.filename()).build().toString());
            response.getOutputStream().write(verified.bytes());
            response.flushBuffer();
        }
    }
}
