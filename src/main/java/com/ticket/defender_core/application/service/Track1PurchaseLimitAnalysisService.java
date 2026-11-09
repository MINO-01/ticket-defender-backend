package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.api.FastApiAdapter;
import com.ticket.defender_core.adapter.out.api.dto.Track1PurchaseLimitAnalysisRequest;
import com.ticket.defender_core.adapter.out.api.dto.Track1PurchaseLimitAnalysisResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Internal-only v2 analysis boundary.  No web controller accepts this payload:
 * the caller must first obtain every field from an authoritative ledger.
 */
@Service
@RequiredArgsConstructor
public class Track1PurchaseLimitAnalysisService {
    private final FastApiAdapter fastApiAdapter;

    @Value("${ticket-defender.track1-v2.enabled:false}")
    private boolean enabled;

    public Track1PurchaseLimitAnalysisResponse analyzeTrustedLedgerInput(
            Track1PurchaseLimitAnalysisRequest request
    ) {
        requireEnabled();
        return fastApiAdapter.requestPurchaseLimitAnalysis(request);
    }

    /** 실행 기록을 만들기 전에 기본 비활성 게이트를 확인합니다. */
    public void requireEnabled() {
        if (!enabled) {
            throw new CaseApiException(HttpStatus.SERVICE_UNAVAILABLE, "TRACK1_V2_DISABLED");
        }
    }
}
