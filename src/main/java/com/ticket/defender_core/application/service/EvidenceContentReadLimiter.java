package com.ticket.defender_core.application.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;

/**
 * 관리자 원본 열람에서 메모리에 올리는 원본 크기와 동시에 처리하는 원본 수를 제한합니다.
 * 허가는 원본을 읽기 전에 얻고 응답 바이트를 다 쓴 뒤 돌려주므로, 메모리에 있는 검증 원본은 허가 수를 넘지 않습니다.
 * 허가가 없으면 기다리지 않고 바로 거절합니다. 기본값은 운영 정책으로 확정된 값이 아닙니다.
 */
@Component
public class EvidenceContentReadLimiter {
    /** 팬 제보 접수의 파일 크기 상한(20 MiB)과 같습니다. 접수에서 받을 수 없는 크기는 열람에서도 허용하지 않습니다. */
    static final long INTAKE_MAX_BYTES = 20L * 1024 * 1024;
    static final int MAX_CONCURRENT_LIMIT = 64;

    private final long maxBytes;
    private final int maxConcurrentReads;
    private final Semaphore permits;

    public EvidenceContentReadLimiter(
            @Value("${ticket-defender.evidence-content.max-bytes:20971520}") long maxBytes,
            @Value("${ticket-defender.evidence-content.max-concurrent-reads:4}") int maxConcurrentReads) {
        if (maxBytes < 1 || maxBytes > INTAKE_MAX_BYTES)
            throw new IllegalStateException("Evidence content max bytes must be between 1 and the intake limit");
        if (maxConcurrentReads < 1 || maxConcurrentReads > MAX_CONCURRENT_LIMIT)
            throw new IllegalStateException("Evidence content concurrent reads must be between 1 and 64");
        this.maxBytes = maxBytes;
        this.maxConcurrentReads = maxConcurrentReads;
        this.permits = new Semaphore(maxConcurrentReads);
    }

    public long maxBytes() {
        return maxBytes;
    }

    public int maxConcurrentReads() {
        return maxConcurrentReads;
    }

    /** 허가를 바로 얻지 못하면 false입니다. 대기열을 만들지 않습니다. */
    boolean tryAcquire() {
        return permits.tryAcquire();
    }

    void release() {
        permits.release();
    }
}
