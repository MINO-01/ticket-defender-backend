package com.ticket.defender_core.adapter.in.web;

import com.ticket.defender_core.adapter.in.web.dto.FanReportLookupRequest;
import com.ticket.defender_core.adapter.in.web.dto.FanReportLookupResponse;
import com.ticket.defender_core.adapter.in.web.dto.FanReportReceipt;
import com.ticket.defender_core.adapter.in.web.dto.FanReportSubmission;
import com.ticket.defender_core.application.service.FanReportIntakeService;
import com.ticket.defender_core.application.service.PublicRequestLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/fan-reports")
@RequiredArgsConstructor
public class FanReportController {
    private final PublicRequestLimiter limiter;
    private final FanReportIntakeService intake;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FanReportReceipt> submit(HttpServletRequest httpRequest,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("Report-Recovery-Secret") String recoverySecret,
            @Valid @RequestPart("report") FanReportSubmission report,
            @RequestPart(value = "evidence", required = false) List<MultipartFile> evidence) {
        limiter.checkIntake(httpRequest.getRemoteAddr());
        FanReportReceipt receipt = intake.submit(report, evidence, idempotencyKey, recoverySecret);
        return ResponseEntity.status(receipt.replayed() ? 200 : 201)
                .cacheControl(CacheControl.noStore()).body(receipt);
    }

    @PostMapping(path = "/lookup", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FanReportLookupResponse> lookup(HttpServletRequest httpRequest,
            @Valid @RequestBody FanReportLookupRequest request) {
        limiter.checkLookup(httpRequest.getRemoteAddr(), request.receiptNumber());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(intake.lookup(request));
    }
}
