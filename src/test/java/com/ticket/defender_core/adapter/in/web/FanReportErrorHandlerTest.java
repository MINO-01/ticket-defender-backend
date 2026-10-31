package com.ticket.defender_core.adapter.in.web;

import com.ticket.defender_core.application.service.FanReportApiException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import software.amazon.awssdk.core.exception.SdkClientException;

import static org.assertj.core.api.Assertions.assertThat;

/** 팬 제보 오류 응답이 확정 실패와 결과 불확실을 구분하는지 검증한다. */
class FanReportErrorHandlerTest {
    private final FanReportErrorHandler handler = new FanReportErrorHandler();

    @Test
    void transientInfrastructureFailuresAreOutcomeUnknownNotConfirmedFailure() {
        for (Exception failure : new Exception[]{
                new CannotAcquireLockException("synthetic quota lock wait timeout"),
                new DataAccessResourceFailureException("synthetic connection loss"),
                SdkClientException.create("synthetic object store outage"),
                new IllegalStateException("synthetic readiness failure")}) {
            ResponseEntity<ProblemDetail> response = handler.unavailable(failure);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getBody().getProperties()).containsEntry("code", "INTAKE_OUTCOME_UNKNOWN");
            assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("5");
            assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
            assertThat(response.getBody().getDetail()).isNull();
        }
    }

    @Test
    void persistedStorageFailureKeepsExistingCodeWithoutRetryAfter() {
        ResponseEntity<ProblemDetail> response = handler.api(
                new FanReportApiException(HttpStatus.SERVICE_UNAVAILABLE, "INTAKE_STORAGE_UNAVAILABLE"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().getProperties()).containsEntry("code", "INTAKE_STORAGE_UNAVAILABLE");
        assertThat(response.getHeaders().getFirst("Retry-After")).isNull();
    }

    @Test
    void rateLimitKeepsRetryAfterSixty() {
        ResponseEntity<ProblemDetail> response = handler.api(
                new FanReportApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("60");
    }
}
