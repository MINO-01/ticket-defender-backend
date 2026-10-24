package com.ticket.defender_core.integration;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaseW05ConcurrencyGateTest {
    @Test
    void readinessTimeoutReleasesWaitingWorkerWithoutStartingItsAction() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean run = new AtomicBoolean();
        try (var worker = Executors.newSingleThreadExecutor()) {
            var result = worker.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) return "BLOCKED";
                return run.get() ? "STARTED" : "RELEASED";
            });
            assertThatThrownBy(() -> CaseW05ConcurrentMySqlTest.awaitReadyAndRelease(
                    ready, start, run, 10, TimeUnit.MILLISECONDS)).isInstanceOf(AssertionError.class);
            assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo("RELEASED");
        }
    }
}
