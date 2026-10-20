package com.ticket.defender_core.adapter.in.web;

import com.ticket.defender_core.application.service.FanReportApiException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import software.amazon.awssdk.core.exception.SdkException;

import java.util.UUID;

@RestControllerAdvice(assignableTypes = FanReportController.class)
public class FanReportErrorHandler {
    @ExceptionHandler(FanReportApiException.class)
    ResponseEntity<ProblemDetail> api(FanReportApiException error) {
        return problem(error.status(), error.code());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class, MissingServletRequestPartException.class,
            IllegalArgumentException.class})
    ResponseEntity<ProblemDetail> invalid(Exception ignored) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REPORT_REQUEST");
    }

    @ExceptionHandler({DataAccessException.class, SdkException.class, IllegalStateException.class})
    ResponseEntity<ProblemDetail> unavailable(Exception ignored) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "INTAKE_STORAGE_UNAVAILABLE");
    }

    static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code) {
        ProblemDetail detail = ProblemDetail.forStatus(status);
        detail.setTitle(status.getReasonPhrase());
        detail.setProperty("code", code);
        detail.setProperty("requestId", UUID.randomUUID().toString());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status).header("Cache-Control", "no-store");
        if (status == HttpStatus.TOO_MANY_REQUESTS) response.header("Retry-After", "60");
        return response.body(detail);
    }
}
