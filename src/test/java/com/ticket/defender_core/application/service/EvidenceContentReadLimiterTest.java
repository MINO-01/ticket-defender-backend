package com.ticket.defender_core.application.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 원본 열람 크기와 동시 읽기 설정값 검증, 허가 반환 동작을 확인합니다. */
class EvidenceContentReadLimiterTest {
    @Test
    void rejectsSettingsOutsideTheIntakeLimitAndSaneConcurrency() {
        assertThatThrownBy(() -> new EvidenceContentReadLimiter(0, 4)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new EvidenceContentReadLimiter(20L * 1024 * 1024 + 1, 4))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new EvidenceContentReadLimiter(1024, 0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new EvidenceContentReadLimiter(1024, 65)).isInstanceOf(IllegalStateException.class);
        EvidenceContentReadLimiter defaults = new EvidenceContentReadLimiter(20L * 1024 * 1024, 4);
        assertThat(defaults.maxBytes()).isEqualTo(20L * 1024 * 1024);
        assertThat(defaults.maxConcurrentReads()).isEqualTo(4);
    }

    @Test
    void permitsAreNotQueuedAndBecomeAvailableAfterRelease() {
        EvidenceContentReadLimiter limiter = new EvidenceContentReadLimiter(1024, 2);
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();
        limiter.release();
        assertThat(limiter.tryAcquire()).isTrue();
    }
}
