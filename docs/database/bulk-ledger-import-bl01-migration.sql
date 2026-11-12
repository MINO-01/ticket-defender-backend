-- BL-01 only registers a metadata job. No object bytes, row payload, HMAC, staging, ledger head, or outbox data is stored here.
CREATE TABLE td_bulk_import_jobs (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_by_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    schema_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    -- Opaque source identifiers are case-sensitive: their uniqueness must agree
    -- with the canonical request digest rather than with the server collation.
    source_system VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    source_batch_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    mode VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    format VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    declared_object_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    declared_file_bytes BIGINT NOT NULL,
    declared_row_count BIGINT NOT NULL,
    complete_snapshot BIT(1) NOT NULL,
    status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    processed_row_count BIGINT NOT NULL,
    accepted_row_count BIGINT NOT NULL,
    rejected_row_count BIGINT NOT NULL,
    attempt_count INT NOT NULL,
    lease_owner VARCHAR(128) NULL,
    lease_until DATETIME(6) NULL,
    fence_token BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    cancelled_at DATETIME(6) NULL,
    row_version BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_td_bulk_import_idempotency (created_by_membership_id, idempotency_key_digest),
    UNIQUE KEY uk_td_bulk_import_source_batch (tenant_id, source_system, source_batch_id),
    UNIQUE KEY uk_td_bulk_import_job_tenant (id, tenant_id),
    KEY idx_td_bulk_import_tenant_created (tenant_id, created_at),
    CONSTRAINT fk_td_bulk_import_actor FOREIGN KEY (tenant_id, created_by_membership_id)
        REFERENCES memberships (tenant_id, id),
    CONSTRAINT chk_td_bulk_import_job_values CHECK (
        mode IN ('FULL_SNAPSHOT', 'CHANGE_FEED') AND format IN ('CSV') AND status IN ('CREATED', 'CANCELLED')
        AND declared_file_bytes >= 0 AND declared_row_count >= 0 AND processed_row_count >= 0
        AND accepted_row_count >= 0 AND rejected_row_count >= 0 AND attempt_count = 0 AND fence_token = 0
        AND processed_row_count = 0 AND accepted_row_count = 0 AND rejected_row_count = 0
        AND lease_owner IS NULL AND lease_until IS NULL
        AND ((status = 'CREATED' AND cancelled_at IS NULL) OR (status = 'CANCELLED' AND cancelled_at IS NOT NULL))
        AND ((mode = 'FULL_SNAPSHOT' AND complete_snapshot = b'1') OR (mode = 'CHANGE_FEED' AND complete_snapshot = b'0'))
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE td_bulk_import_scopes (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scope_as_of DATETIME(6) NULL,
    declared_row_count BIGINT NOT NULL,
    cursor_start VARCHAR(256) NULL,
    cursor_end VARCHAR(256) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_td_bulk_import_scope (job_id, event_id, performance_id),
    KEY idx_td_bulk_import_scope_performance (tenant_id, event_id, performance_id),
    CONSTRAINT fk_td_bulk_import_scope_job FOREIGN KEY (job_id, tenant_id)
        REFERENCES td_bulk_import_jobs (id, tenant_id),
    CONSTRAINT fk_td_bulk_import_scope_performance FOREIGN KEY (tenant_id, event_id, performance_id)
        REFERENCES verified_performances (tenant_id, event_id, id),
    CONSTRAINT chk_td_bulk_import_scope_values CHECK (
        declared_row_count >= 0
        AND ((scope_as_of IS NOT NULL AND cursor_start IS NULL AND cursor_end IS NULL)
             OR (scope_as_of IS NULL AND cursor_start IS NOT NULL AND cursor_end IS NOT NULL))
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE td_bulk_import_errors (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sequence_no BIGINT NOT NULL,
    code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_td_bulk_import_error_sequence (job_id, sequence_no),
    KEY idx_td_bulk_import_error_job (job_id, sequence_no),
    CONSTRAINT fk_td_bulk_import_error_job FOREIGN KEY (job_id) REFERENCES td_bulk_import_jobs (id),
    CONSTRAINT chk_td_bulk_import_error_values CHECK (sequence_no >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
