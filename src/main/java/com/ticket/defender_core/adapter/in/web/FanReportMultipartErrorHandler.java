package com.ticket.defender_core.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FanReportMultipartErrorHandler {
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ProblemDetail> tooLarge(HttpServletRequest request, MaxUploadSizeExceededException error) {
        if (!"/api/v1/fan-reports".equals(request.getServletPath())) throw error;
        return FanReportErrorHandler.problem(HttpStatus.PAYLOAD_TOO_LARGE, "REQUEST_TOO_LARGE");
    }
}
