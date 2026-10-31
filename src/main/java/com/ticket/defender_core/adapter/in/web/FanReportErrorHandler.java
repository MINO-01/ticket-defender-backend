package com.ticket.defender_core.adapter.in.web;

import com.ticket.defender_core.application.service.FanReportApiException;
import com.ticket.defender_core.application.service.FanReportIntakeService;
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

    /**
     * 요청 제한 테이블 잠금이나 일시적인 DB 및 저장소 장애는 영속된 실패 상태를 확인한 것이 아니므로
     * 확정 실패 코드로 바꾸지 않고 같은 키 재시도용 결과 불확실 코드로 응답한다.
     */
    @ExceptionHandler({DataAccessException.class, SdkException.class, IllegalStateException.class})
    ResponseEntity<ProblemDetail> unavailable(Exception ignored) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, FanReportIntakeService.OUTCOME_UNKNOWN_CODE);
    }

    static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code) {
        ProblemDetail detail = ProblemDetail.forStatus(status);
        detail.setTitle(status.getReasonPhrase());
        detail.setProperty("code", code);
        detail.setProperty("requestId", UUID.randomUUID().toString());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status).header("Cache-Control", "no-store");
        if (status == HttpStatus.TOO_MANY_REQUESTS) response.header("Retry-After", "60");
        if (FanReportIntakeService.OUTCOME_UNKNOWN_CODE.equals(code)) response.header("Retry-After", "5");
        return response.body(detail);
    }
}
