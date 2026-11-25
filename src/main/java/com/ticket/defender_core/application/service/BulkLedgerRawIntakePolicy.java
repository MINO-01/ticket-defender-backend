package com.ticket.defender_core.application.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * v3 원본 신호 수신의 운영 조건입니다. 기본값은 비활성화입니다.
 * 켜려면 업로드 원본 정리 작업도 켜져 있어야 하며, 아니면 시작에 실패합니다. 시작할 때 저장소에서 버전 삭제가 실제로
 * 되는지 확인하고, 확인되지 않으면 v3만 거절합니다. v1과 v2 동작은 이 정책의 영향을 받지 않습니다.
 */
@Component
public class BulkLedgerRawIntakePolicy {
    static final String DISABLED = "BULK_V3_RAW_INTAKE_DISABLED";
    static final String DELETE_UNVERIFIED = "BULK_V3_DELETE_PERMISSION_UNVERIFIED";
    private final boolean enabled;
    private final boolean cleanupEnabled;
    private final Duration retention;
    private final BulkLedgerUploadStorage storage;
    private volatile boolean deletePermissionVerified;

    public BulkLedgerRawIntakePolicy(
            @Value("${ticket-defender.bulk-ledger-upload.v3-raw-intake.enabled:false}") boolean enabled,
            @Value("${ticket-defender.bulk-ledger-upload.cleanup.enabled:false}") boolean cleanupEnabled,
            @Value("${ticket-defender.bulk-ledger-upload.cleanup.retention:PT24H}") Duration retention,
            BulkLedgerUploadStorage storage) {
        this.enabled = enabled;
        this.cleanupEnabled = cleanupEnabled;
        this.retention = retention;
        this.storage = storage;
    }

    @PostConstruct
    void verifyAtStartup() {
        if (retention == null || retention.isNegative() || retention.isZero() || retention.compareTo(Duration.ofHours(24)) > 0)
            throw new IllegalStateException("Bulk ledger upload cleanup retention must be positive and at most 24 hours");
        if (!enabled) return;
        if (!cleanupEnabled)
            throw new IllegalStateException("v3 raw intake requires ticket-defender.bulk-ledger-upload.cleanup.enabled=true");
        try {
            deletePermissionVerified = storage.verifyDeletePermission();
        } catch (RuntimeException unverified) {
            deletePermissionVerified = false;
        }
    }

    public boolean v3Accepted() {
        return enabled && deletePermissionVerified;
    }

    /** v3 등록, 업로드 세션, 업로드 완료, 스테이징 직전에 호출합니다. */
    public void requireV3Accepted() {
        if (!enabled) throw new CaseApiException(HttpStatus.UNPROCESSABLE_ENTITY, DISABLED);
        if (!deletePermissionVerified) throw new CaseApiException(HttpStatus.SERVICE_UNAVAILABLE, DELETE_UNVERIFIED);
    }

    Duration retention() { return retention; }
}
