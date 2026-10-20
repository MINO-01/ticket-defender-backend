package com.ticket.defender_core.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ticket.defender_core.domain.FanReport;

import java.time.OffsetDateTime;
import java.util.UUID;

public record FanReportReceipt(UUID fanReportId, String receiptNumber, String lookupCode,
                               FanReport.IntakeStatus intakeStatus, OffsetDateTime receivedAt,
                               @JsonIgnore boolean replayed) {
    public FanReportReceipt(UUID fanReportId, String receiptNumber, String lookupCode,
                            FanReport.IntakeStatus intakeStatus, OffsetDateTime receivedAt) {
        this(fanReportId, receiptNumber, lookupCode, intakeStatus, receivedAt, false);
    }
}
