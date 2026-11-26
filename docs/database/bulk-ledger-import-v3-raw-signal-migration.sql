-- BL-V3: 대량 CSV v3 원본 신호 수신을 위한 additive 확장입니다.
-- 적용 순서: bulk-ledger-import-bl05-publication-migration.sql 다음.
-- 스테이징 행에는 백엔드가 읽는 즉시 계산한 HMAC 토큰과 출처 표시만 저장하며 원본 값은 저장하지 않습니다.
-- 기존 v1, v2 행은 새 컬럼이 모두 NULL입니다.
ALTER TABLE td_bulk_import_staging_rows
    ADD COLUMN payment_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN address_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN device_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN ip_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN refund_account_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN phone_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN phone_source VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN ip_network_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN ip_network_type_source VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD CONSTRAINT chk_td_bulk_staging_v3_signals CHECK (
        (phone_source IS NULL) = (phone_hmac IS NULL)
        AND (phone_source IS NULL OR phone_source IN ('RECIPIENT','MEMBER'))
        AND (ip_network_type IS NULL OR ip_hmac IS NOT NULL)
        AND (ip_network_type IS NULL OR ip_network_type IN
             ('MOBILE_CGNAT','RESIDENTIAL','CORPORATE_EDU','DATACENTER_PROXY','UNKNOWN'))
        AND (ip_network_type_source IS NULL) = (ip_network_type IS NULL)
        AND (ip_network_type_source IS NULL OR ip_network_type_source IN ('DECLARED','CLASSIFIED','UNKNOWN'))
    );

-- 업로드 원본 객체 삭제 결과입니다. 기존 작업 상태 전이와 분리해 기록합니다.
-- 파일 SHA-256, 바이트 수, 행 수는 td_bulk_import_jobs에 그대로 남아 출처 증명에 쓰입니다.
CREATE TABLE IF NOT EXISTS td_bulk_import_upload_deletions (
    import_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    trigger_reason VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    last_failure_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    last_attempt_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (import_id),
    KEY idx_td_bulk_upload_deletion_status (tenant_id, status),
    CONSTRAINT fk_td_bulk_upload_deletion_job FOREIGN KEY (import_id, tenant_id) REFERENCES td_bulk_import_jobs (id, tenant_id),
    CONSTRAINT chk_td_bulk_upload_deletion_values CHECK (
        status IN ('DELETED','FAILED')
        AND attempt_count >= 0
        AND trigger_reason IN ('STAGING_COMPLETED','STAGING_REJECTED','RETENTION_EXPIRED')
        AND ((status = 'DELETED') = (deleted_at IS NOT NULL))
        AND (status <> 'FAILED' OR last_failure_code IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
