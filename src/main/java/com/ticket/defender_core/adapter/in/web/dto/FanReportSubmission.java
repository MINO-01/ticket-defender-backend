package com.ticket.defender_core.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.ticket.defender_core.domain.FanReport;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.UUID;

public record FanReportSubmission(
        @NotNull UUID eventId,
        @NotNull LocalDateTime performanceAt,
        @Size(max = 160) String seatArea,
        @NotNull FanReport.ReportType reportType,
        @NotBlank @Size(min = 20, max = 1000) String description,
        @Size(max = 2048) @Pattern(regexp = "|https?://[^\\s]+") String listingUrl,
        @Size(max = 160) String platform,
        @Size(max = 160) String seller,
        @Pattern(regexp = "|[1-9][0-9]{0,11}") String askingPrice
) {
    public FanReportSubmission {
        if (listingUrl != null && !listingUrl.isBlank()) {
            try {
                URI uri = URI.create(listingUrl);
                if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                        || uri.getHost() == null || uri.getHost().isBlank())
                    throw new IllegalArgumentException("Invalid listing URL");
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("Invalid listing URL");
            }
        }
    }

    @JsonAnySetter
    public void rejectUnknownProperty(String name, Object value) {
        throw new IllegalArgumentException("Unknown report field");
    }

    public BigDecimal parsedAskingPrice() {
        return askingPrice == null || askingPrice.isBlank() ? null : new BigDecimal(askingPrice);
    }
}
