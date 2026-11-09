package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.Track1QuantityDto;
import com.ticket.defender_core.adapter.out.api.FastApiAuthenticationConfigurationException;
import com.ticket.defender_core.adapter.out.api.FastApiResponseContractException;
import com.ticket.defender_core.adapter.out.api.dto.Track1PurchaseLimitAnalysisResponse;
import com.ticket.defender_core.domain.Track1PurchaseLimitRun;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.net.SocketTimeoutException;
import java.util.UUID;

/**
 * 원장 검증, FastAPI v2 호출, 결과 저장을 잇습니다. 이 클래스는 트랜잭션을 열지 않으므로
 * 외부 호출 동안 DB 연결과 잠금을 잡지 않습니다. 실패는 절대 정상 0건으로 바꾸지 않습니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Track1QuantityAnalysisRunService {
    private final Track1QuantityRunStore store;
    private final Track1PurchaseLimitAnalysisService analysis;

    public Track1QuantityDto.AnalysisRun start(UUID eventId, String idempotencyKey, Track1QuantityDto.RunRequest input) {
        Track1QuantityRunStore.Opened opened = store.open(eventId, idempotencyKey, input);
        if (opened.call() == null) return opened.finished();
        Track1PurchaseLimitAnalysisResponse response;
        try {
            response = analysis.analyzeTrustedLedgerInput(opened.call());
        } catch (RuntimeException failure) {
            Failure mapped = classify(failure);
            log.warn("Track 1 v2 분석 실패. 실행 ID: {}, 코드: {}", opened.runId(), mapped.code());
            return store.fail(opened.runId(), mapped.status(), mapped.code());
        }
        try {
            return store.complete(opened, response);
        } catch (RuntimeException failure) {
            log.warn("Track 1 v2 결과 저장 실패. 실행 ID: {}, 유형: {}", opened.runId(), failure.getClass().getSimpleName());
            return store.fail(opened.runId(), Track1PurchaseLimitRun.Execution.ANALYZER_FAILED, "RESULT_PERSISTENCE_FAILED");
        }
    }

    public Track1QuantityDto.Page<Track1QuantityDto.AnalysisRun> list(UUID eventId, int page, int pageSize) {
        return store.list(eventId, page, pageSize);
    }

    public Track1QuantityDto.AnalysisRun get(UUID eventId, UUID runId) {
        return store.get(eventId, runId);
    }

    private static Failure classify(RuntimeException failure) {
        if (failure instanceof FastApiResponseContractException)
            return new Failure(Track1PurchaseLimitRun.Execution.ANALYZER_FAILED, "ANALYZER_CONTRACT_VIOLATION");
        if (failure instanceof FastApiAuthenticationConfigurationException)
            return new Failure(Track1PurchaseLimitRun.Execution.ANALYZER_FAILED, "ANALYZER_AUTH_CONFIGURATION");
        if (failure instanceof ResourceAccessException)
            return new Failure(Track1PurchaseLimitRun.Execution.ANALYZER_UNAVAILABLE,
                    failure.getCause() instanceof SocketTimeoutException ? "ANALYZER_TIMEOUT" : "ANALYZER_UNREACHABLE");
        if (failure instanceof HttpServerErrorException)
            return new Failure(Track1PurchaseLimitRun.Execution.ANALYZER_UNAVAILABLE, "ANALYZER_HTTP_5XX");
        if (failure instanceof HttpClientErrorException)
            return new Failure(Track1PurchaseLimitRun.Execution.ANALYZER_FAILED, "ANALYZER_HTTP_4XX");
        if (failure instanceof RestClientException)
            return new Failure(Track1PurchaseLimitRun.Execution.ANALYZER_UNAVAILABLE, "ANALYZER_UNAVAILABLE");
        return new Failure(Track1PurchaseLimitRun.Execution.ANALYZER_FAILED, "ANALYSIS_FAILED");
    }

    private record Failure(Track1PurchaseLimitRun.Execution status, String code) { }
}
