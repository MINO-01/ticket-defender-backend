package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.AgentAnalysisRequest;
import com.ticket.defender_core.adapter.out.api.FastApiAdapter;
import com.ticket.defender_core.adapter.out.api.dto.MacroAnalysisRequest;
import com.ticket.defender_core.adapter.out.api.dto.MacroAnalysisResponse;
import com.ticket.defender_core.security.AnalysisIngestAccessService;
import com.ticket.defender_core.security.AnalysisIngestScope;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class FraudAnalysisService {

    private static final int MAX_LOCK_RETRIES = 2;
    private static final int MYSQL_LOCK_WAIT_TIMEOUT = 1205;
    private static final int MYSQL_DEADLOCK = 1213;

    private final FastApiAdapter fastApiAdapter;
    private final MacroAuditPersistenceService macroAuditPersistenceService;
    private final AnalysisIngestAccessService ingestAccessService;

    /** 요청 데이터를 분석 서버로 보내고 완료된 결과만 저장합니다. */
    public FraudAnalysisStatus processAgentData(AgentAnalysisRequest request) {
        AnalysisIngestScope scope = ingestAccessService.requireScope(request);
        String requestId = UUID.randomUUID().toString();
        List<MacroAnalysisRequest.Ticket> tickets = request.tickets().stream()
                .map(ticket -> new MacroAnalysisRequest.Ticket(
                        ticket.accountId(),
                        ticket.reservationNo(),
                        ticket.eventId(),
                        ticket.paymentHash(),
                        ticket.addressHash(),
                        ticket.deviceIdHash(),
                        ticket.ipHash()
                ))
                .toList();
        MacroAnalysisRequest analysisRequest = new MacroAnalysisRequest(requestId, tickets);

        MacroAnalysisResponse response = fastApiAdapter.requestMacroAnalysis(analysisRequest);
        if (response == null || response.status() != MacroAnalysisResponse.Status.COMPLETED) {
            log.warn("그래프 분석을 완료하지 못해 결과를 저장하지 않습니다. 요청 ID: {}", requestId);
            return FraudAnalysisStatus.UNAVAILABLE;
        }
        if (!requestId.equals(response.requestId())) {
            throw new IllegalStateException("분석 응답의 요청 ID가 현재 요청과 일치하지 않습니다.");
        }
        if (response.clusters() == null) {
            throw new IllegalStateException("완료된 분석 응답에 군집 목록이 없습니다.");
        }
        if (response.clusters().isEmpty()) {
            log.info("그래프 분석이 완료됐으며 조사 대상 군집이 없습니다. 요청 ID: {}", requestId);
            return FraudAnalysisStatus.COMPLETED;
        }

        persistWithRetry(response, analysisRequest, scope);
        return FraudAnalysisStatus.COMPLETED;
    }

    private void persistWithRetry(MacroAnalysisResponse response, MacroAnalysisRequest request,
                                  AnalysisIngestScope scope) {
        int uniqueRetries = 0;
        int lockRetries = 0;
        while (true) {
            try {
                // This call crosses the other bean's @Transactional proxy on every attempt.
                macroAuditPersistenceService.persistDetectedAudits(response, request, scope);
                return;
            } catch (DataIntegrityViolationException concurrentInsert) {
                if (uniqueRetries++ >= 1) throw concurrentInsert;
                log.info("동시 분석 요청이 같은 매크로 증거를 저장했습니다. 기존 증거를 다시 확인합니다. 요청 ID: {}",
                        response.requestId());
            } catch (CannotAcquireLockException lockFailure) {
                int mysqlError = retryableMySqlLockError(lockFailure);
                if (mysqlError == 0 || lockRetries >= MAX_LOCK_RETRIES) {
                    if (mysqlError != 0) {
                        log.warn("매크로 증거 저장 잠금 재시도 소진. 요청 ID: {}, MySQL 오류: {}",
                                response.requestId(), mysqlError);
                    }
                    throw lockFailure;
                }
                lockRetries++;
                log.warn("매크로 증거 저장 잠금 충돌 재시도. 요청 ID: {}, MySQL 오류: {}, 재시도: {}/{}",
                        response.requestId(), mysqlError, lockRetries, MAX_LOCK_RETRIES);
                pauseBeforeLockRetry(lockRetries);
            }
        }
    }

    private int retryableMySqlLockError(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                for (SQLException candidate = sql; candidate != null; candidate = candidate.getNextException()) {
                    if (candidate.getErrorCode() == MYSQL_DEADLOCK
                            && "40001".equals(candidate.getSQLState())) return MYSQL_DEADLOCK;
                    if (candidate.getErrorCode() == MYSQL_LOCK_WAIT_TIMEOUT
                            && "40001".equals(candidate.getSQLState())) return MYSQL_LOCK_WAIT_TIMEOUT;
                }
            }
        }
        return 0;
    }

    private void pauseBeforeLockRetry(int attempt) {
        long delayMillis = ThreadLocalRandom.current().nextLong(10, 31) * attempt;
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("매크로 증거 저장 재시도가 중단됐습니다.", interrupted);
        }
    }
}
