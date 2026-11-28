-- PR 3: supplier-code import registration and optional v3 ticket fields.
-- Apply after current-schema-baseline.sql and the existing bulk-ledger migrations.
-- Additive only. No existing event, performance, import, or ledger rows are removed.
ALTER TABLE td_bulk_import_jobs
    ADD COLUMN scope_key_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'TD_UUID';

ALTER TABLE verified_performances
    ADD COLUMN performance_starts_at DATETIME(6) NULL,
    ADD COLUMN supplier_per_account_limit INT NULL;

ALTER TABLE verified_performances
    ADD CONSTRAINT chk_verified_performance_supplier_limit
        CHECK (supplier_per_account_limit IS NULL OR supplier_per_account_limit > 0);

ALTER TABLE td_bulk_import_staging_rows
    ADD COLUMN seat_zone VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD COLUMN seat_row VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD COLUMN seat_number VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD COLUMN reserved_at DATETIME(6) NULL,
    ADD COLUMN paid_at DATETIME(6) NULL,
    ADD COLUMN cancelled_at DATETIME(6) NULL,
    ADD COLUMN account_created_at DATETIME(6) NULL;

ALTER TABLE td_bulk_ledger_ticket_versions
    ADD COLUMN seat_zone VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD COLUMN seat_row VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD COLUMN seat_number VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    ADD COLUMN reserved_at DATETIME(6) NULL,
    ADD COLUMN paid_at DATETIME(6) NULL,
    ADD COLUMN cancelled_at DATETIME(6) NULL,
    ADD COLUMN account_created_at DATETIME(6) NULL;

CREATE TABLE td_bulk_import_supplier_scopes (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_event_code VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    source_performance_code VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    event_title VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    performance_starts_at DATETIME(6) NOT NULL,
    per_account_limit INT NULL,
    scope_as_of DATETIME(6) NULL,
    declared_row_count BIGINT NOT NULL,
    cursor_start VARCHAR(256) NULL,
    cursor_end VARCHAR(256) NULL,
    source_sequence_start BIGINT NULL,
    source_sequence_end BIGINT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    event_created BIT(1) NOT NULL DEFAULT b'0',
    performance_created BIT(1) NOT NULL DEFAULT b'0',
    PRIMARY KEY (id),
    UNIQUE KEY uk_td_bulk_supplier_scope_code (job_id, source_event_code, source_performance_code),
    KEY idx_td_bulk_supplier_scope_tenant (tenant_id, job_id),
    CONSTRAINT fk_td_bulk_supplier_scope_job FOREIGN KEY (job_id, tenant_id)
        REFERENCES td_bulk_import_jobs (id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT fk_td_bulk_supplier_scope_event FOREIGN KEY (tenant_id, event_id)
        REFERENCES events (tenant_id, id),
    CONSTRAINT fk_td_bulk_supplier_scope_performance FOREIGN KEY (tenant_id, event_id, performance_id)
        REFERENCES verified_performances (tenant_id, event_id, id),
    CONSTRAINT chk_td_bulk_supplier_scope_values CHECK (
        declared_row_count >= 0 AND (per_account_limit IS NULL OR per_account_limit > 0)
        AND ((event_id IS NULL AND performance_id IS NULL) OR (event_id IS NOT NULL AND performance_id IS NOT NULL))
        AND ((source_sequence_start IS NULL AND source_sequence_end IS NULL)
            OR (source_sequence_start IS NOT NULL AND source_sequence_end IS NOT NULL
                AND source_sequence_start >= 0 AND source_sequence_end >= source_sequence_start))
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
