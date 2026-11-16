-- BL-05 additive atomic publication. Candidate rows prepared before expected-head capture are intentionally not publishable.
-- BL-01 predates v2 source checkpoints. Keep its v1 shapes while admitting the v2 FULL_SNAPSHOT checkpoint.
ALTER TABLE td_bulk_import_scopes DROP CHECK chk_td_bulk_import_scope_values;
ALTER TABLE td_bulk_import_scopes ADD CONSTRAINT chk_td_bulk_import_scope_values CHECK (
    declared_row_count >= 0
    AND (
        (scope_as_of IS NOT NULL AND cursor_start IS NULL
            AND ((cursor_end IS NULL AND source_sequence_start IS NULL AND source_sequence_end IS NULL)
              OR (cursor_end IS NOT NULL AND source_sequence_start IS NULL AND source_sequence_end IS NOT NULL AND source_sequence_end >= 0)))
        OR (scope_as_of IS NULL AND cursor_start IS NOT NULL AND cursor_end IS NOT NULL
            AND ((source_sequence_start IS NULL AND source_sequence_end IS NULL)
              OR (source_sequence_start IS NOT NULL AND source_sequence_end IS NOT NULL
                  AND source_sequence_start >= 0 AND source_sequence_end >= source_sequence_start)))
    )
);

ALTER TABLE td_bulk_import_jobs DROP CHECK chk_td_bulk_import_job_values;
ALTER TABLE td_bulk_import_jobs ADD CONSTRAINT chk_td_bulk_import_job_values CHECK (
    mode IN ('FULL_SNAPSHOT', 'CHANGE_FEED') AND format = 'CSV'
    AND status IN ('CREATED','UPLOAD_PENDING','UPLOADED','VALIDATING','STAGING','READY_TO_PUBLISH','PUBLISHED','FAILED_RETRYABLE','REJECTED','CANCELLED')
    AND declared_file_bytes >= 0 AND declared_row_count >= 0 AND processed_row_count >= 0 AND accepted_row_count >= 0 AND rejected_row_count >= 0
    AND processed_row_count = accepted_row_count + rejected_row_count
    AND ((mode = 'FULL_SNAPSHOT' AND complete_snapshot = b'1') OR (mode = 'CHANGE_FEED' AND complete_snapshot = b'0'))
    AND ((status = 'CREATED' AND upload_object_key_digest IS NULL AND upload_object_version IS NULL AND upload_expires_at IS NULL)
      OR (status = 'UPLOAD_PENDING' AND upload_object_key_digest IS NOT NULL AND upload_object_version IS NULL AND upload_expires_at IS NOT NULL)
      OR (status IN ('UPLOADED','VALIDATING','STAGING','READY_TO_PUBLISH','PUBLISHED','FAILED_RETRYABLE','REJECTED')
          AND upload_object_key_digest IS NOT NULL AND upload_object_version IS NOT NULL)
      OR status = 'CANCELLED')
    AND ((status IN ('VALIDATING','STAGING') AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
      OR (status NOT IN ('VALIDATING','STAGING') AND lease_owner IS NULL AND lease_until IS NULL))
    AND ((status = 'CANCELLED' AND cancelled_at IS NOT NULL)
      OR (status <> 'CANCELLED' AND cancelled_at IS NULL))
);

ALTER TABLE td_bulk_ledger_candidate_imports DROP CHECK chk_td_bulk_candidate_import_state;
ALTER TABLE td_bulk_ledger_candidate_imports
    ADD COLUMN source_object_version VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL AFTER tenant_id,
    ADD COLUMN source_object_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER source_object_version;
ALTER TABLE td_bulk_ledger_candidate_imports ADD CONSTRAINT chk_td_bulk_candidate_import_state CHECK (
    (status IN ('PREPARING','READY','PUBLISHED') AND failure_code IS NULL) OR (status = 'REJECTED' AND failure_code IS NOT NULL)
);

ALTER TABLE td_bulk_ledger_versions
    ADD COLUMN expected_head_known TINYINT(1) NOT NULL DEFAULT 0 AFTER base_version_id,
    ADD COLUMN expected_head_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER expected_head_known,
    ADD KEY idx_td_bulk_ledger_version_expected_head (expected_head_version_id),
    ADD CONSTRAINT fk_td_bulk_ledger_version_expected_head FOREIGN KEY (expected_head_version_id) REFERENCES td_bulk_ledger_versions (id),
    ADD CONSTRAINT chk_td_bulk_ledger_version_expected_head CHECK (expected_head_known IN (0,1) AND (expected_head_known = 1 OR expected_head_version_id IS NULL));

CREATE TABLE td_bulk_ledger_publications (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    import_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    requester_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    published_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_td_bulk_ledger_publication_import (import_id),
    UNIQUE KEY uk_td_bulk_ledger_publication_idempotency (requester_membership_id,idempotency_key_digest),
    CONSTRAINT fk_td_bulk_ledger_publication_job FOREIGN KEY (import_id,tenant_id) REFERENCES td_bulk_import_jobs (id,tenant_id),
    CONSTRAINT fk_td_bulk_ledger_publication_actor FOREIGN KEY (tenant_id,requester_membership_id) REFERENCES memberships (tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE td_bulk_ledger_publication_history (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    publication_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    import_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    previous_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    generation_no BIGINT NOT NULL,
    revision_no BIGINT NOT NULL,
    source_system VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    source_batch_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    published_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_td_bulk_ledger_publication_history_scope (publication_id,performance_id),
    KEY idx_td_bulk_ledger_history_import (import_id,performance_id),
    CONSTRAINT fk_td_bulk_ledger_history_publication FOREIGN KEY (publication_id) REFERENCES td_bulk_ledger_publications (id),
    CONSTRAINT fk_td_bulk_ledger_history_previous FOREIGN KEY (previous_version_id) REFERENCES td_bulk_ledger_versions (id),
    CONSTRAINT fk_td_bulk_ledger_history_version FOREIGN KEY (version_id) REFERENCES td_bulk_ledger_versions (id),
    CONSTRAINT fk_td_bulk_ledger_history_performance FOREIGN KEY (tenant_id,event_id,performance_id) REFERENCES verified_performances (tenant_id,event_id,id),
    CONSTRAINT chk_td_bulk_ledger_history_version CHECK (generation_no >= 0 AND revision_no >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE td_bulk_ledger_publication_outbox (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    publication_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    history_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    import_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    deduplication_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    delivery_attempt_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_td_bulk_ledger_outbox_history (history_id),
    UNIQUE KEY uk_td_bulk_ledger_outbox_publication_scope (publication_id,performance_id),
    UNIQUE KEY uk_td_bulk_ledger_outbox_deduplication (deduplication_key),
    KEY idx_td_bulk_ledger_outbox_pending (status,created_at),
    CONSTRAINT fk_td_bulk_ledger_outbox_publication FOREIGN KEY (publication_id) REFERENCES td_bulk_ledger_publications (id),
    CONSTRAINT fk_td_bulk_ledger_outbox_history FOREIGN KEY (history_id) REFERENCES td_bulk_ledger_publication_history (id),
    CONSTRAINT fk_td_bulk_ledger_outbox_version FOREIGN KEY (version_id) REFERENCES td_bulk_ledger_versions (id),
    CONSTRAINT chk_td_bulk_ledger_outbox_values CHECK (event_type = 'LEDGER_PUBLISHED' AND status = 'PENDING' AND delivery_attempt_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
