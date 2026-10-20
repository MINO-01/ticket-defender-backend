package com.ticket.defender_core.adapter.in.web.dto;

import com.ticket.defender_core.domain.FanReport;

import java.time.OffsetDateTime;

public record FanReportLookupResponse(FanReport.IntakeStatus intakeStatus, OffsetDateTime receivedAt) {}
