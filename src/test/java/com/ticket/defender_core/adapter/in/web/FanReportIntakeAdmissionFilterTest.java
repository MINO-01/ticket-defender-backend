package com.ticket.defender_core.adapter.in.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FanReportIntakeAdmissionFilterTest {
    @Test
    void interruptedRequestIsRejectedAndInterruptFlagIsPreserved() throws Exception {
        FanReportIntakeAdmissionFilter filter = new FanReportIntakeAdmissionFilter(1);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/fan-reports");
        request.setServletPath("/api/v1/fan-reports");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        Thread.currentThread().interrupt();
        try {
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(503);
            assertThat(response.getContentAsString()).contains("INTAKE_BUSY");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(chain.getRequest()).isNull();
        } finally {
            Thread.interrupted();
        }
        MockHttpServletRequest nextRequest = new MockHttpServletRequest("POST", "/api/v1/fan-reports");
        nextRequest.setServletPath("/api/v1/fan-reports");
        MockHttpServletResponse next = new MockHttpServletResponse();
        filter.doFilter(nextRequest, next, new MockFilterChain());
        assertThat(next.getStatus()).isEqualTo(200);
    }

    @Test
    void unsafeConfiguredConcurrencyFailsAtStartup() {
        assertThatThrownBy(() -> new FanReportIntakeAdmissionFilter(Integer.MAX_VALUE))
                .isInstanceOf(IllegalStateException.class);
    }
}
