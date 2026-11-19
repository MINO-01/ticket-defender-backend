-- Issue #99 additive MySQL 8.0 migration. Apply after bulk-ledger-track1-bulk-job-migration.sql.
-- It never rewrites existing BL-07 jobs or results.
ALTER TABLE td_bulk_ledger_track1_bulk_jobs
    ADD COLUMN collection_phase VARCHAR(32) NULL AFTER result_cursor,
    ADD COLUMN shared_signal_cursor VARCHAR(512) NULL AFTER collection_phase,
    ADD COLUMN purchase_limit_evaluation_cursor VARCHAR(512) NULL AFTER shared_signal_cursor;

CREATE TABLE td_bulk_ledger_track1_policy_snapshots (
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ledger_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_version VARCHAR(128) NULL, source_as_of DATETIME(6) NULL,
    ledger_complete_snapshot BIT(1) NOT NULL,
    policy_verification_status VARCHAR(32) NOT NULL,
    quantity_data_status VARCHAR(32) NOT NULL,
    data_completeness_reason VARCHAR(128) NULL,
    cancellation_handling VARCHAR(32) NOT NULL,
    duplicate_handling VARCHAR(32) NOT NULL,
    policy_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    policy_revision INT NULL, policy_version VARCHAR(128) NULL,
    max_tickets_per_account INT NULL,
    effective_from DATETIME(6) NULL, effective_until DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (job_id), UNIQUE KEY uk_bl07_policy_snapshot_id (snapshot_id),
    KEY idx_bl07_policy_snapshot_ledger (ledger_version_id),
    CONSTRAINT fk_bl07_policy_snapshot_job FOREIGN KEY (job_id) REFERENCES td_bulk_ledger_track1_bulk_jobs(id),
    CONSTRAINT chk_bl07_policy_snapshot_state CHECK (
        (policy_verification_status='CONFIRMED' AND policy_id IS NOT NULL AND policy_revision >= 1
            AND policy_version IS NOT NULL AND max_tickets_per_account >= 1 AND effective_from IS NOT NULL)
        OR (policy_verification_status='POLICY_NOT_CONFIRMED' AND policy_id IS NULL AND policy_revision IS NULL
            AND policy_version IS NULL AND max_tickets_per_account IS NULL AND effective_from IS NULL AND effective_until IS NULL)
    ),
    CONSTRAINT chk_bl07_policy_snapshot_quantity CHECK (quantity_data_status IN ('COMPLETE','INCOMPLETE')),
    CONSTRAINT chk_bl07_policy_snapshot_times CHECK (effective_until IS NULL OR effective_until > effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE td_bulk_ledger_track1_bulk_shared_signal_observations (
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    observation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scope_type VARCHAR(16) NOT NULL, signal_type VARCHAR(16) NOT NULL,
    distinct_account_count INT NOT NULL, scope_account_count INT NOT NULL, distinct_reservation_count INT NOT NULL,
    total_ticket_quantity BIGINT NULL, ticket_quantity_status VARCHAR(16) NOT NULL,
    payment_shared_account_count INT NOT NULL, device_shared_account_count INT NOT NULL,
    ip_network_type VARCHAR(32) NOT NULL, purchase_window_seconds BIGINT NULL,
    excluded_from_louvain BIT(1) NOT NULL,
    PRIMARY KEY(job_id,observation_id), KEY idx_bl07_shared_signal_scope(job_id,performance_id,signal_type),
    CONSTRAINT fk_bl07_shared_signal_job FOREIGN KEY(job_id) REFERENCES td_bulk_ledger_track1_bulk_jobs(id),
    CONSTRAINT chk_bl07_shared_signal_values CHECK (distinct_account_count >= 2 AND scope_account_count >= 2
        AND distinct_reservation_count >= 1 AND (total_ticket_quantity IS NULL OR total_ticket_quantity >= 1)),
    CONSTRAINT chk_bl07_shared_signal_counts CHECK (payment_shared_account_count >= 0 AND device_shared_account_count >= 0
        AND (purchase_window_seconds IS NULL OR purchase_window_seconds >= 0)),
    CONSTRAINT chk_bl07_shared_signal_status CHECK (ticket_quantity_status IN ('VERIFIED','UNAVAILABLE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE td_bulk_ledger_track1_bulk_limit_evaluations (
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    internal_event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    external_event_id VARCHAR(128) NOT NULL, evaluation_status VARCHAR(48) NOT NULL,
    policy_version VARCHAR(128) NULL, expected_finding_count INT NOT NULL,
    source_cursor VARCHAR(512) NULL, findings_cursor VARCHAR(512) NULL, findings_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    PRIMARY KEY(job_id,performance_id), KEY idx_bl07_limit_evaluation_status(job_id,findings_status),
    CONSTRAINT fk_bl07_limit_evaluation_job FOREIGN KEY(job_id) REFERENCES td_bulk_ledger_track1_bulk_jobs(id),
    CONSTRAINT chk_bl07_limit_evaluation_status CHECK (evaluation_status IN ('EVALUATED','POLICY_NOT_CONFIRMED','QUANTITY_DATA_INCOMPLETE','POLICY_TIME_NOT_APPLICABLE')),
    CONSTRAINT chk_bl07_limit_evaluation_count CHECK (expected_finding_count >= 0),
    CONSTRAINT chk_bl07_limit_evaluation_collection CHECK (findings_status IN ('PENDING','COLLECTING','COMPLETED','NOT_APPLICABLE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE td_bulk_ledger_track1_bulk_limit_findings (
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_reference_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    effective_ticket_quantity BIGINT NOT NULL, max_tickets_per_account INT NOT NULL, policy_version VARCHAR(128) NOT NULL,
    PRIMARY KEY(job_id,performance_id,account_reference_hmac),
    CONSTRAINT fk_bl07_limit_finding_evaluation FOREIGN KEY(job_id,performance_id)
        REFERENCES td_bulk_ledger_track1_bulk_limit_evaluations(job_id,performance_id),
    CONSTRAINT chk_bl07_limit_finding_quantities CHECK (effective_ticket_quantity >= 1 AND max_tickets_per_account >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE td_bulk_ledger_track1_bulk_limit_finding_reservations (
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_reference_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reservation_reference_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    effective_ticket_quantity BIGINT NOT NULL,
    PRIMARY KEY(job_id,performance_id,account_reference_hmac,reservation_reference_hmac),
    CONSTRAINT fk_bl07_limit_finding_reservation FOREIGN KEY(job_id,performance_id,account_reference_hmac)
        REFERENCES td_bulk_ledger_track1_bulk_limit_findings(job_id,performance_id,account_reference_hmac),
    CONSTRAINT chk_bl07_limit_finding_reservation_quantity CHECK (effective_ticket_quantity >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
