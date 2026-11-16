-- BL-04 candidate preparation recovery metadata. Candidate preparation remains private and never publishes a ledger head.
ALTER TABLE td_bulk_ledger_candidate_imports
    ADD COLUMN preparation_attempt_count INT NOT NULL DEFAULT 0 AFTER failure_code,
    ADD COLUMN preparation_fence_token BIGINT NOT NULL DEFAULT 0 AFTER preparation_attempt_count,
    ADD COLUMN preparation_owner VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER preparation_attempt_count,
    ADD COLUMN preparation_lease_until DATETIME(6) NULL AFTER preparation_owner,
    ADD COLUMN last_retry_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER preparation_lease_until;

CREATE INDEX idx_td_bulk_candidate_prepare_lease
    ON td_bulk_ledger_candidate_imports (status, preparation_lease_until, updated_at);
