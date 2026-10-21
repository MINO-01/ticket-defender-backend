package com.ticket.defender_core.adapter.in.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "ticket-defender.fan-report.enabled", havingValue = "true")
public class FanReportIntakeAdmissionFilter extends OncePerRequestFilter {
    private static final long MIB = 1024L * 1024;
    private final Semaphore permits;

    public FanReportIntakeAdmissionFilter(
            @Value("${ticket-defender.fan-report.max-concurrent-intakes:0}") int configuredLimit) {
        // One request may retain 100 MiB of originals and decode a 20M-pixel image.
        // Leave a base heap reserve for Spring and other requests.
        long safeLimit = Math.min(4, (Runtime.getRuntime().maxMemory() - 256 * MIB) / (256 * MIB));
        if (safeLimit < 1 || configuredLimit < 0 || (configuredLimit > 0 && configuredLimit > safeLimit))
            throw new IllegalStateException("Fan report intake concurrency exceeds the available JVM heap");
        permits = new Semaphore(configuredLimit == 0 ? (int) safeLimit : configuredLimit);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod())
                || !"/api/v1/fan-reports".equals(request.getServletPath());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(0, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            busy(response);
            return;
        }
        if (!acquired) {
            busy(response);
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            permits.release();
        }
    }

    private void busy(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Retry-After", "1");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Service Unavailable\","
                + "\"status\":503,\"code\":\"INTAKE_BUSY\",\"requestId\":\""
                + UUID.randomUUID() + "\"}");
    }
}
