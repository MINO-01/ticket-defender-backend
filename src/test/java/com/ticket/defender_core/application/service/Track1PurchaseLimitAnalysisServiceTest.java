package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.api.FastApiAdapter;
import com.ticket.defender_core.adapter.out.api.dto.Track1PurchaseLimitAnalysisRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class Track1PurchaseLimitAnalysisServiceTest {
    @Test
    void remainsDisabledByDefaultAndNeverCallsDataWithUnsanctionedInput() {
        FastApiAdapter adapter = mock(FastApiAdapter.class);
        Track1PurchaseLimitAnalysisService service = new Track1PurchaseLimitAnalysisService(adapter);
        ReflectionTestUtils.setField(service, "enabled", false);

        assertThatThrownBy(() -> service.analyzeTrustedLedgerInput(mock(Track1PurchaseLimitAnalysisRequest.class)))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("TRACK1_V2_DISABLED"));
        verifyNoInteractions(adapter);
    }
}
