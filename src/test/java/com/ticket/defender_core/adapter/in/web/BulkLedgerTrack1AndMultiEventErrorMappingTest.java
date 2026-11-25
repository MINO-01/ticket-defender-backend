package com.ticket.defender_core.adapter.in.web;

import com.ticket.defender_core.application.service.BulkLedgerTrack1AnalysisQueryService;
import com.ticket.defender_core.application.service.BulkLedgerTrack1BulkQueryService;
import com.ticket.defender_core.application.service.BulkLedgerTrack1SignalService;
import com.ticket.defender_core.application.service.CaseApiException;
import com.ticket.defender_core.application.service.MultiEventInvestigationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 대량 원장 Track 1 신호 API와 다공연 조사 API의 업무 오류가 500으로 바뀌지 않고 고정 코드로 응답되는지 확인합니다.
 * 이전에는 두 컨트롤러가 공통 오류 처리 대상에 없어 CaseApiException이 모두 500이었습니다.
 */
class BulkLedgerTrack1AndMultiEventErrorMappingTest {
    @Test
    void ledgerSignalConflictIsA409WithItsCode() throws Exception {
        BulkLedgerTrack1SignalService signals = mock(BulkLedgerTrack1SignalService.class);
        doThrow(new CaseApiException(HttpStatus.CONFLICT, "LEDGER_SIGNAL_ALREADY_RECORDED")).when(signals).register(any(), any(), any());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new BulkLedgerTrack1Controller(signals, mock(BulkLedgerTrack1AnalysisQueryService.class),
                mock(BulkLedgerTrack1BulkQueryService.class))).setControllerAdvice(new CaseErrorHandler()).build();

        mvc.perform(post("/api/v1/admin/events/" + UUID.randomUUID() + "/ledger-track1/versions/" + UUID.randomUUID() + "/signals")
                        .contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEDGER_SIGNAL_ALREADY_RECORDED"));
    }

    @Test
    void multiPerformanceScopeAndRetiredScoreVersionErrorsKeepTheirCodes() throws Exception {
        MultiEventInvestigationService service = mock(MultiEventInvestigationService.class);
        when(service.create(anyString(), any())).thenThrow(new CaseApiException(HttpStatus.CONFLICT, "MULTI_EVENT_PERFORMANCE_SCOPE_REQUIRED"));
        when(service.retry(any(), any())).thenThrow(new CaseApiException(HttpStatus.CONFLICT, "SCORE_VERSION_RETIRED"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new MultiEventInvestigationController(service)).setControllerAdvice(new CaseErrorHandler()).build();

        mvc.perform(post("/api/v1/admin/multi-event-investigations").header("Idempotency-Key", "k-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"eventIds\":[\"" + UUID.randomUUID() + "\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MULTI_EVENT_PERFORMANCE_SCOPE_REQUIRED"));
        mvc.perform(post("/api/v1/admin/multi-event-investigations/events/" + UUID.randomUUID() + "/" + UUID.randomUUID() + "/retry"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SCORE_VERSION_RETIRED"));
    }
}
