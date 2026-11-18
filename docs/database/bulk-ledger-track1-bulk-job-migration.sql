-- BL-07: durable Spring-owned state for Data #36 bulk jobs. Apply after BL-06.
CREATE TABLE IF NOT EXISTS td_bulk_ledger_track1_bulk_jobs (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    outbox_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ledger_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    artifact_id VARCHAR(128) NULL, object_key VARCHAR(128) NULL, object_version VARCHAR(128) NULL,
    artifact_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL, record_count BIGINT NULL,
    source_version VARCHAR(128) NULL, source_as_of DATETIME(6) NULL, hmac_key_version VARCHAR(128) NULL,
    policy_version VARCHAR(128) NULL, data_job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    data_status VARCHAR(32) NULL, status VARCHAR(32) NOT NULL, failure_code VARCHAR(64) NULL,
    algorithm_version VARCHAR(160) NULL, shared_signal_observations_status VARCHAR(32) NULL,
    purchase_limit_evaluations_status VARCHAR(32) NULL, result_cursor VARCHAR(512) NULL,
    lease_owner VARCHAR(128) NULL, lease_until DATETIME(6) NULL, fence_token BIGINT NOT NULL DEFAULT 0,
    attempt_count INT NOT NULL DEFAULT 0, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (id), UNIQUE KEY uk_bl07_job_outbox (outbox_id), UNIQUE KEY uk_bl07_data_job (data_job_id),
    KEY idx_bl07_claim (status, lease_until, created_at), KEY idx_bl07_scope (tenant_id,event_id,performance_id,created_at),
    CONSTRAINT fk_bl07_outbox FOREIGN KEY (outbox_id) REFERENCES td_bulk_ledger_publication_outbox(id),
    CONSTRAINT chk_bl07_counts CHECK (record_count IS NULL OR record_count >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS td_bulk_ledger_track1_bulk_clusters (
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, cluster_id VARCHAR(128) NOT NULL,
    risk_score DOUBLE NOT NULL, member_count INT NOT NULL, payment_hashes_json JSON NOT NULL,
    address_hashes_json JSON NOT NULL, device_hashes_json JSON NOT NULL, ip_hashes_json JSON NOT NULL,
    PRIMARY KEY(job_id,cluster_id), CONSTRAINT fk_bl07_cluster_job FOREIGN KEY(job_id) REFERENCES td_bulk_ledger_track1_bulk_jobs(id),
    CONSTRAINT chk_bl07_cluster_values CHECK(risk_score >= 0 AND risk_score <= 1 AND member_count >= 2)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS td_bulk_ledger_track1_bulk_members (
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, cluster_id VARCHAR(128) NOT NULL,
    account_reference_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reservation_reference_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY(job_id,cluster_id,account_reference_hmac,reservation_reference_hmac),
    CONSTRAINT fk_bl07_member_cluster FOREIGN KEY(job_id,cluster_id) REFERENCES td_bulk_ledger_track1_bulk_clusters(job_id,cluster_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
