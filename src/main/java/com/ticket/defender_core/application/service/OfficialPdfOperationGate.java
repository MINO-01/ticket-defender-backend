package com.ticket.defender_core.application.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * A feature flag is insufficient for an operating issuance policy. Until governance approves a
 * real policy, only explicitly isolated synthetic environments may turn this path on.
 */
@Component
@RequiredArgsConstructor
public class OfficialPdfOperationGate {
    @Value("${ticket-defender.official-pdf.enabled:false}")
    private boolean enabled;
    @Value("${ticket-defender.official-pdf.operational-mode:DISABLED}")
    private String operationalMode;

    public boolean enabledForSyntheticIsolation() {
        return enabled && "SYNTHETIC_ISOLATED".equals(operationalMode);
    }

    public void requireEnabled() {
        if (!enabled) throw new CaseApiException(HttpStatus.CONFLICT, "OFFICIAL_PDF_DISABLED");
        if (!"SYNTHETIC_ISOLATED".equals(operationalMode))
            throw new CaseApiException(HttpStatus.CONFLICT, "OFFICIAL_PDF_OPERATION_NOT_APPROVED");
    }
}
