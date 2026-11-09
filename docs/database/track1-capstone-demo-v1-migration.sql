-- Apply after track1-purchase-ledger-migration.sql in the isolated MySQL 8 demonstration environment.
-- Existing v2 rows cannot be re-derived: their source reservation reference is deliberately not retained.
-- CI exercises both a legacy schema and a Hibernate-created current schema, so additive columns are conditional.
SET @td_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
    AND table_name = 'track1_purchase_ledger_rows' AND column_name = 'track2_reservation_ref_hmac') = 0,
    'ALTER TABLE track1_purchase_ledger_rows ADD COLUMN track2_reservation_ref_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL', 'SELECT 1');
PREPARE td_stmt FROM @td_sql; EXECUTE td_stmt; DEALLOCATE PREPARE td_stmt;

SET @td_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
    AND table_name = 'track1_purchase_ledger_batches' AND column_name = 'track2_link_digest') = 0,
    'ALTER TABLE track1_purchase_ledger_batches ADD COLUMN track2_link_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL', 'SELECT 1');
PREPARE td_stmt FROM @td_sql; EXECUTE td_stmt; DEALLOCATE PREPARE td_stmt;

SET @td_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
    AND table_name = 'macro_audit_evidence' AND column_name = 'reference_purpose') = 0,
    'ALTER TABLE macro_audit_evidence ADD COLUMN reference_purpose VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT ''LEGACY_V1_UNLINKABLE''', 'SELECT 1');
PREPARE td_stmt FROM @td_sql; EXECUTE td_stmt; DEALLOCATE PREPARE td_stmt;

-- Do not populate this column by rehashing TRACK1_QTY_RESERVATION values.
SET @td_sql = IF((SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema = DATABASE()
    AND table_name = 'track1_purchase_ledger_rows' AND constraint_name = 'chk_t1_ledger_track2_reference') = 0,
    'ALTER TABLE track1_purchase_ledger_rows ADD CONSTRAINT chk_t1_ledger_track2_reference CHECK (track2_reservation_ref_hmac IS NULL OR CHAR_LENGTH(track2_reservation_ref_hmac) = 64)', 'SELECT 1');
PREPARE td_stmt FROM @td_sql; EXECUTE td_stmt; DEALLOCATE PREPARE td_stmt;

CREATE TABLE IF NOT EXISTS track1_purchase_limit_shared_signal_observations (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    observation_id VARCHAR(128) NOT NULL,
    scope_type VARCHAR(64) NOT NULL,
    scope_id VARCHAR(128) NOT NULL,
    signal_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    distinct_account_count INT NOT NULL,
    scope_account_count INT NOT NULL,
    distinct_reservation_count INT NOT NULL,
    total_ticket_quantity INT NULL,
    ticket_quantity_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payment_shared_account_count INT NOT NULL,
    device_shared_account_count INT NOT NULL,
    ip_network_type VARCHAR(64) NULL,
    purchase_window_seconds INT NULL,
    excluded_from_louvain BIT(1) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_t1_signal_observation_run_id (run_id, observation_id),
    KEY idx_t1_signal_observation_run (run_id),
    CONSTRAINT fk_t1_signal_observation_run FOREIGN KEY (run_id) REFERENCES track1_purchase_limit_runs (id),
    CONSTRAINT chk_t1_signal_observation_values CHECK (
        signal_type IN ('IP', 'ADDRESS') AND ticket_quantity_status IN ('VERIFIED', 'UNAVAILABLE')
        AND distinct_account_count >= 0 AND scope_account_count >= 0 AND distinct_reservation_count >= 0
        AND payment_shared_account_count >= 0 AND device_shared_account_count >= 0
        AND (purchase_window_seconds IS NULL OR purchase_window_seconds >= 0)
        AND ((ticket_quantity_status = 'VERIFIED' AND total_ticket_quantity >= 0)
             OR (ticket_quantity_status = 'UNAVAILABLE' AND total_ticket_quantity IS NULL))
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
