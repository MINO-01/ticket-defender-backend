-- BL-02 additive migration. Existing BL-01 jobs remain valid and no public ledger table is written.
ALTER TABLE td_bulk_import_jobs
    ADD COLUMN upload_object_key_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER declared_row_count,
    ADD COLUMN upload_object_version VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL AFTER upload_object_key_digest,
    ADD COLUMN upload_expires_at DATETIME(6) NULL AFTER upload_object_version;
ALTER TABLE td_bulk_import_jobs DROP CHECK chk_td_bulk_import_job_values;
ALTER TABLE td_bulk_import_jobs ADD CONSTRAINT chk_td_bulk_import_job_values CHECK (
    mode IN ('FULL_SNAPSHOT', 'CHANGE_FEED') AND format = 'CSV'
    AND status IN ('CREATED','UPLOAD_PENDING','UPLOADED','VALIDATING','STAGING','READY_TO_PUBLISH','FAILED_RETRYABLE','REJECTED','CANCELLED')
    AND declared_file_bytes >= 0 AND declared_row_count >= 0 AND processed_row_count >= 0 AND accepted_row_count >= 0 AND rejected_row_count >= 0
    AND processed_row_count = accepted_row_count + rejected_row_count
    AND ((mode = 'FULL_SNAPSHOT' AND complete_snapshot = b'1') OR (mode = 'CHANGE_FEED' AND complete_snapshot = b'0'))
    AND ((status = 'CREATED' AND upload_object_key_digest IS NULL AND upload_object_version IS NULL AND upload_expires_at IS NULL)
      OR (status = 'UPLOAD_PENDING' AND upload_object_key_digest IS NOT NULL AND upload_object_version IS NULL AND upload_expires_at IS NOT NULL)
      OR (status IN ('UPLOADED','VALIDATING','STAGING','READY_TO_PUBLISH','FAILED_RETRYABLE','REJECTED') AND upload_object_key_digest IS NOT NULL AND upload_object_version IS NOT NULL)
      OR status = 'CANCELLED')
    AND ((status IN ('VALIDATING','STAGING') AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
      OR (status NOT IN ('VALIDATING','STAGING') AND lease_owner IS NULL AND lease_until IS NULL))
    AND ((status = 'CANCELLED' AND cancelled_at IS NOT NULL)
      OR (status <> 'CANCELLED' AND cancelled_at IS NULL))
);

CREATE TABLE td_bulk_import_staging_rows (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    import_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_row_no BIGINT NOT NULL,
    reservation_reference_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ticket_reference_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_reference_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    hmac_key_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    purchased_at DATETIME(6) NULL,
    ticket_quantity INT NOT NULL,
    source_event_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    source_changed_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_td_bulk_staging_import_row (import_id, source_row_no),
    KEY idx_td_bulk_staging_scope (tenant_id, event_id, performance_id),
    CONSTRAINT fk_td_bulk_staging_job FOREIGN KEY (import_id, tenant_id) REFERENCES td_bulk_import_jobs (id, tenant_id),
    CONSTRAINT fk_td_bulk_staging_performance FOREIGN KEY (tenant_id, event_id, performance_id) REFERENCES verified_performances (tenant_id, event_id, id),
    CONSTRAINT chk_td_bulk_staging_values CHECK (source_row_no > 0 AND ticket_quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
