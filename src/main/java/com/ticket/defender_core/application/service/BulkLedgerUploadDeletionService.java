package com.ticket.defender_core.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * 업로드 원본 객체를 스테이징 성공 또는 확정 거절 직후에 지우고 결과를 따로 기록합니다.
 * 삭제 실패는 작업 상태 전이를 바꾸지 않습니다. 파일 SHA-256, 바이트 수, 행 수는 작업 행에 그대로 남습니다.
 */
@Service
public class BulkLedgerUploadDeletionService {
    static final int MAX_ATTEMPTS = 3;
    /** 정리 작업이 원본을 지운 미처리 업로드의 확정 실패 코드입니다. */
    public static final String UPLOAD_OBJECT_EXPIRED = "UPLOAD_OBJECT_EXPIRED";
    private static final Logger log = LoggerFactory.getLogger(BulkLedgerUploadDeletionService.class);
    private final JdbcTemplate jdbc;
    private final BulkLedgerUploadStorage storage;
    private final BulkLedgerRawIntakePolicy policy;
    private final boolean cleanupEnabled;

    public BulkLedgerUploadDeletionService(JdbcTemplate jdbc, BulkLedgerUploadStorage storage, BulkLedgerRawIntakePolicy policy,
            @Value("${ticket-defender.bulk-ledger-upload.cleanup.enabled:false}") boolean cleanupEnabled) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.policy = policy;
        this.cleanupEnabled = cleanupEnabled;
    }

    public enum Reason { STAGING_COMPLETED, STAGING_REJECTED, RETENTION_EXPIRED }

    public record Summary(long deleted, long failedAfterRetries) { }

    /** 최대 3회 시도하고 결과를 기록합니다. 어떤 실패도 호출자에게 전파하지 않습니다. */
    public boolean deleteUpload(UUID importId, UUID tenantId, Reason reason) {
        String failure = null;
        int attempts = 0;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            attempts = attempt;
            try {
                storage.deleteAllVersions(objectKey(importId));
                failure = null;
                break;
            } catch (BulkLedgerUploadStorage.InvalidObject invalid) {
                failure = "UPLOAD_DELETE_OBJECT_INVALID";
            } catch (RuntimeException unavailable) {
                failure = "UPLOAD_DELETE_UNAVAILABLE";
            }
        }
        try {
            record(importId, tenantId, reason, attempts, failure);
        } catch (RuntimeException recordFailure) {
            // 기록 실패도 작업 상태를 바꾸지 않습니다. 정리 작업이 다시 시도합니다.
            log.warn("Bulk upload deletion result could not be recorded (code={})", "UPLOAD_DELETE_RECORD_FAILED");
            return false;
        }
        if (failure != null) log.warn("Bulk upload object deletion failed (code={}, attempts={})", failure, attempts);
        return failure == null;
    }

    /** 보존 한도(기본 24시간)를 넘긴 미처리, 만료, 삭제 실패 업로드 객체를 지웁니다. */
    @Scheduled(fixedDelayString = "${ticket-defender.bulk-ledger-upload.cleanup.delay:PT1H}")
    public void cleanupExpired() {
        if (!cleanupEnabled) return;
        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minus(policy.retention());
        List<Expired> expired = jdbc.query("""
                select j.id,j.tenant_id from td_bulk_import_jobs j
                left join td_bulk_import_upload_deletions d on d.import_id=j.id
                where j.upload_object_key_digest is not null and j.created_at < ?
                  and (j.status not in ('VALIDATING','STAGING') or j.lease_until is null or j.lease_until < utc_timestamp(6))
                  and (d.import_id is null or d.status='FAILED')
                order by j.created_at,j.id limit 100
                """, (rs, n) -> new Expired(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2))), Timestamp.valueOf(cutoff));
        for (Expired job : expired) deleteUpload(job.importId(), job.tenantId(), Reason.RETENTION_EXPIRED);
    }

    /** 보존 한도 초과로 정리 작업이 원본을 지운 업로드인지 삭제 기록으로 판정합니다. 이런 업로드는 다시 처리할 수 없습니다. */
    public boolean retentionExpired(UUID importId) {
        Integer expired = jdbc.queryForObject("""
                select count(*) from td_bulk_import_upload_deletions
                where import_id=? and status='DELETED' and trigger_reason='RETENTION_EXPIRED'
                """, Integer.class, importId.toString());
        return expired != null && expired > 0;
    }

    public Summary summary(UUID tenantId) {
        Long deleted = jdbc.queryForObject("select count(*) from td_bulk_import_upload_deletions where tenant_id=? and status='DELETED'",
                Long.class, tenantId.toString());
        Long failed = jdbc.queryForObject("select count(*) from td_bulk_import_upload_deletions where tenant_id=? and status='FAILED' and attempt_count>=?",
                Long.class, tenantId.toString(), MAX_ATTEMPTS);
        return new Summary(deleted == null ? 0 : deleted, failed == null ? 0 : failed);
    }

    private void record(UUID importId, UUID tenantId, Reason reason, int attempts, String failure) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now(ZoneOffset.UTC));
        String status = failure == null ? "DELETED" : "FAILED";
        jdbc.update("""
                insert into td_bulk_import_upload_deletions(import_id,tenant_id,status,attempt_count,trigger_reason,last_failure_code,last_attempt_at,deleted_at,created_at,updated_at)
                values(?,?,?,?,?,?,?,?,?,?) as incoming
                on duplicate key update status=incoming.status,attempt_count=td_bulk_import_upload_deletions.attempt_count+incoming.attempt_count,
                  trigger_reason=incoming.trigger_reason,last_failure_code=incoming.last_failure_code,last_attempt_at=incoming.last_attempt_at,
                  deleted_at=incoming.deleted_at,updated_at=incoming.updated_at
                """, importId.toString(), tenantId.toString(), status, attempts, reason.name(), failure, now,
                failure == null ? now : null, now, now);
    }

    static String objectKey(UUID importId) { return "bulk-ledger-private/" + importId + "/upload.csv"; }
    private record Expired(UUID importId, UUID tenantId) { }
}
