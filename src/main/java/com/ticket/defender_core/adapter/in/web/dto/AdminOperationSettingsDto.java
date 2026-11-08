package com.ticket.defender_core.adapter.in.web.dto;

/** Deliberately contains only boolean/categorical operational state, never endpoint or credential configuration. */
public record AdminOperationSettingsDto(
        boolean fanReportIntakeEnabled,
        boolean track2ListingAnalysisConfigured,
        String track1RelationshipEvidenceCoverage,
        String officialPdfIssuanceState,
        String auditorAccessState
) { }
