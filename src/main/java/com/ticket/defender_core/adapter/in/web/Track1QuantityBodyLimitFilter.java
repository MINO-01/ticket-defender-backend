package com.ticket.defender_core.adapter.in.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** JSON 본문은 multipart 제한이 적용되지 않으므로 원장 반입 본문 크기를 파싱 전에 제한합니다. 길이를 알 수 없는 요청은 거절합니다. */
@Component
public class Track1QuantityBodyLimitFilter extends OncePerRequestFilter {
    static final long MAX_BODY_BYTES = 2L * 1024 * 1024;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !request.getRequestURI().endsWith("/track1-quantity/ledger-imports");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long length = request.getContentLengthLong();
        if (length < 0 || length > MAX_BODY_BYTES) {
            response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
            response.setContentType("application/problem+json");
            response.getWriter().write("{\"status\":413,\"code\":\"LEDGER_IMPORT_BODY_TOO_LARGE\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
