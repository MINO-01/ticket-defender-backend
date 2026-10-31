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
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestControllerAdvice(assignableTypes = {CaseController.class, CasePreparationController.class,
        ReportDraftController.class, EvidenceIntegrityController.class, ApprovalRequestController.class,
        OfficialPdfIssuanceController.class})
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

    static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code) {
        ProblemDetail detail = ProblemDetail.forStatus(status);
        detail.setTitle(status.getReasonPhrase());
        detail.setProperty("code", code);
        detail.setProperty("requestId", UUID.randomUUID().toString());
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(detail);
    }
}
