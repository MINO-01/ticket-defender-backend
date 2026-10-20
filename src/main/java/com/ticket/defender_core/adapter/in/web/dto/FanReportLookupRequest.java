package com.ticket.defender_core.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FanReportLookupRequest(@NotBlank @Size(max = 40) String receiptNumber,
                                     @NotBlank @Size(max = 64) String lookupCode) {
    @JsonAnySetter
    public void rejectUnknownProperty(String name, Object value) {
        throw new IllegalArgumentException("Unknown lookup field");
    }
}
