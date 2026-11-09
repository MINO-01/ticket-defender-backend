package com.ticket.defender_core.adapter.in.web;

import com.ticket.defender_core.application.service.CaseApiException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.UUID;

@RestControllerAdvice(assignableTypes = {CaseController.class, CasePreparationController.class,
        ReportDraftController.class, EvidenceIntegrityController.class, ApprovalRequestController.class,
        OfficialPdfIssuanceController.class, ListingContextAnalysisController.class,
        AdminFanReportController.class, AdminAnalysisRunController.class, AdminEventController.class,
        VerifiedPerformanceController.class,
        PerformanceLinkController.class, ReservationImportController.class, ListingCandidateMatchController.class,
        PurchaseLimitPolicyController.class, Track1QuantityController.class, AdminConsoleController.class})
public class CaseErrorHandler {
    @ExceptionHandler(CaseApiException.class)
    ResponseEntity<ProblemDetail> api(CaseApiException error) {
        return problem(error.status(), error.code());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> optimistic(Exception ignored) {
        return problem(HttpStatus.CONFLICT, "CASE_VERSION_CONFLICT");
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemDetail> resource(ResponseStatusException error) {
        return problem(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> denied(Exception ignored) {
        return problem(HttpStatus.FORBIDDEN, "FORBIDDEN");
    }

    @ExceptionHandler(InsufficientAuthenticationException.class)
    ResponseEntity<ProblemDetail> unauthenticated(Exception ignored) {
        return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestHeaderException.class,
            MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> malformed(Exception ignored) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProblemDetail> database(Exception ignored) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE");
    }

    /**
     * 트랜잭션 시작 실패는 DataAccessException 계층이 아니므로 원인이 DB 연결 획득 실패일 때만 503으로 응답합니다.
     * 그 밖의 트랜잭션 시작 오류는 의존 서비스 장애로 단정하지 않고 다시 던져 기본 500 처리에 맡깁니다.
     */
    @ExceptionHandler(CannotCreateTransactionException.class)
    ResponseEntity<ProblemDetail> transactionStart(CannotCreateTransactionException error) {
        if (!causedByConnectionFailure(error)) throw error;
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE");
    }

    /** JDBC 표준 연결 예외 타입이나 SQL 표준 연결 예외 분류(SQLState 08)가 원인 체인에 있는지 확인합니다. */
    private static boolean causedByConnectionFailure(Throwable error) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = error.getCause(); cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof SQLTransientConnectionException || cause instanceof SQLNonTransientConnectionException)
                return true;
            if (cause instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("08"))
                return true;
        }
        return false;
    }

    static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code) {
        ProblemDetail detail = ProblemDetail.forStatus(status);
        detail.setTitle(status.getReasonPhrase());
        detail.setProperty("code", code);
        detail.setProperty("requestId", UUID.randomUUID().toString());
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(detail);
    }
}
