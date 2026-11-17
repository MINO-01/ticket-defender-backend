-- BL-06: consumption is opt-in and only records analysis work for an already-published immutable version.
ALTER TABLE td_bulk_ledger_publication_outbox DROP CHECK chk_td_bulk_ledger_outbox_values;
ALTER TABLE td_bulk_ledger_publication_outbox
    ADD COLUMN lease_owner VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER delivery_attempt_count,
    ADD COLUMN lease_until DATETIME(6) NULL AFTER lease_owner,
    ADD COLUMN fence_token BIGINT NOT NULL DEFAULT 0 AFTER lease_until,
    ADD COLUMN last_error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER fence_token,
    ADD CONSTRAINT chk_td_bulk_ledger_outbox_values CHECK (
        event_type = 'LEDGER_PUBLISHED'
        AND status IN ('PENDING','PROCESSING','DELIVERED','RETRYABLE','BLOCKED')
        AND delivery_attempt_count >= 0 AND fence_token >= 0
        AND ((status = 'PROCESSING') = (lease_owner IS NOT NULL AND lease_until IS NOT NULL))
    );

CREATE TABLE td_bulk_ledger_track1_analysis_jobs (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    outbox_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    failure_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_td_bl06_analysis_outbox (outbox_id),
    KEY idx_td_bl06_analysis_scope (tenant_id,event_id,performance_id,created_at),
    CONSTRAINT fk_td_bl06_analysis_outbox FOREIGN KEY (outbox_id) REFERENCES td_bulk_ledger_publication_outbox (id),
    CONSTRAINT fk_td_bl06_analysis_version FOREIGN KEY (version_id) REFERENCES td_bulk_ledger_versions (id),
    CONSTRAINT chk_td_bl06_analysis_state CHECK (
        status IN ('PENDING','PROCESSING','SUCCEEDED','RETRYABLE','BLOCKED') AND attempt_count >= 0
        AND ((status = 'SUCCEEDED' AND run_id IS NOT NULL AND failure_code IS NULL)
          OR (status = 'BLOCKED' AND failure_code IS NOT NULL)
          OR (status IN ('PENDING','PROCESSING','RETRYABLE') AND failure_code IS NULL))
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Connection tokens are supplied explicitly after publication. They are never inferred from a reference HMAC.
CREATE TABLE td_bulk_ledger_track1_ticket_signals (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ticket_reference_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    hmac_key_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    hmac_purpose VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payment_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    address_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    device_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ip_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    source_version VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_td_bl06_signal_ticket (version_id,ticket_reference_hmac),
    CONSTRAINT fk_td_bl06_signal_version FOREIGN KEY (version_id) REFERENCES td_bulk_ledger_versions (id),
    CONSTRAINT chk_td_bl06_signal_values CHECK (
        hmac_purpose = 'TRACK1_CONNECTION'
        AND (payment_hmac IS NOT NULL OR address_hmac IS NOT NULL OR device_hmac IS NOT NULL OR ip_hmac IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Reuse the established Track 1 result tables without manufacturing a legacy ledger batch.
ALTER TABLE track1_purchase_limit_runs DROP CHECK chk_t1_limit_run_values;
ALTER TABLE track1_purchase_limit_runs DROP FOREIGN KEY fk_t1_limit_run_batch;
ALTER TABLE track1_purchase_limit_runs MODIFY ledger_batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL;
ALTER TABLE track1_purchase_limit_runs
    ADD COLUMN ledger_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER ledger_batch_id,
    ADD COLUMN publication_outbox_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER ledger_version_id,
    ADD COLUMN active_ledger_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER active_ledger_batch_id,
    -- One immutable outbox job owns the final outcome. Individual failed attempts remain auditable,
    -- so publication_outbox_id is indexed rather than unique.
    ADD KEY idx_t1_limit_run_outbox (publication_outbox_id),
    ADD UNIQUE KEY uk_t1_limit_run_active_version (active_ledger_version_id),
    ADD CONSTRAINT fk_t1_limit_run_batch FOREIGN KEY (ledger_batch_id) REFERENCES track1_purchase_ledger_batches (id),
    ADD CONSTRAINT fk_t1_limit_run_version FOREIGN KEY (ledger_version_id) REFERENCES td_bulk_ledger_versions (id),
    ADD CONSTRAINT fk_t1_limit_run_outbox FOREIGN KEY (publication_outbox_id) REFERENCES td_bulk_ledger_publication_outbox (id),
    ADD CONSTRAINT chk_t1_limit_run_values CHECK (
        execution_status IN ('IN_PROGRESS', 'SUCCEEDED', 'INPUT_REJECTED', 'ANALYZER_UNAVAILABLE', 'ANALYZER_FAILED', 'INTERRUPTED')
        AND (cluster_count IS NULL OR cluster_count >= 0) AND netted_excluded_row_count >= 0
        AND ((ledger_batch_id IS NOT NULL) <> (ledger_version_id IS NOT NULL))
        AND ((execution_status = 'IN_PROGRESS') = ((active_ledger_batch_id IS NOT NULL) <> (active_ledger_version_id IS NOT NULL)))
        AND (execution_status <> 'SUCCEEDED' OR (evaluation_status IS NOT NULL AND cluster_count IS NOT NULL AND error_code IS NULL))
        AND (execution_status IN ('IN_PROGRESS', 'SUCCEEDED') OR (error_code IS NOT NULL AND evaluation_status IS NULL))
    );
