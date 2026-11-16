-- BL-03 additive candidate-ledger preparation. No statement in this migration publishes a ledger head.
ALTER TABLE td_bulk_import_scopes
    ADD COLUMN source_sequence_start BIGINT NULL AFTER cursor_end,
    ADD COLUMN source_sequence_end BIGINT NULL AFTER source_sequence_start;

ALTER TABLE td_bulk_import_staging_rows
    ADD COLUMN quantity_unit VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER ticket_quantity,
    ADD COLUMN ticket_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER quantity_unit,
    ADD COLUMN source_version VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL AFTER ticket_status,
    ADD COLUMN source_sequence BIGINT NULL AFTER source_changed_at;

CREATE TABLE td_bulk_ledger_candidate_imports (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    import_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    failure_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_td_bulk_candidate_import (import_id),
    KEY idx_td_bulk_candidate_import_tenant (tenant_id, status),
    CONSTRAINT fk_td_bulk_candidate_import_job FOREIGN KEY (import_id, tenant_id) REFERENCES td_bulk_import_jobs (id, tenant_id),
    CONSTRAINT chk_td_bulk_candidate_import_state CHECK ((status IN ('PREPARING','READY') AND failure_code IS NULL) OR (status = 'REJECTED' AND failure_code IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE td_bulk_ledger_versions (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    candidate_import_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    import_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    generation_no BIGINT NOT NULL,
    revision_no BIGINT NOT NULL,
    base_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    source_system VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    source_batch_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    source_as_of DATETIME(6) NULL,
    source_cursor VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    source_sequence_end BIGINT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_td_bulk_ledger_version_number (tenant_id, performance_id, generation_no, revision_no),
    UNIQUE KEY uk_td_bulk_ledger_version_import_scope (import_id, performance_id),
    KEY idx_td_bulk_ledger_version_base (base_version_id),
    CONSTRAINT fk_td_bulk_ledger_version_candidate FOREIGN KEY (candidate_import_id) REFERENCES td_bulk_ledger_candidate_imports (id),
    CONSTRAINT fk_td_bulk_ledger_version_import FOREIGN KEY (import_id, tenant_id) REFERENCES td_bulk_import_jobs (id, tenant_id),
    CONSTRAINT fk_td_bulk_ledger_version_performance FOREIGN KEY (tenant_id, event_id, performance_id) REFERENCES verified_performances (tenant_id, event_id, id),
    CONSTRAINT fk_td_bulk_ledger_version_base FOREIGN KEY (base_version_id) REFERENCES td_bulk_ledger_versions (id),
    CONSTRAINT chk_td_bulk_ledger_version_values CHECK (generation_no >= 0 AND revision_no >= 0 AND (source_sequence_end IS NULL OR source_sequence_end >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE td_bulk_ledger_ticket_versions (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ticket_reference_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reservation_reference_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_reference_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    hmac_key_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    purchased_at DATETIME(6) NOT NULL,
    ticket_quantity INT NOT NULL,
    quantity_unit VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ticket_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_version VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    source_event_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_td_bulk_ledger_ticket_version (version_id, ticket_reference_hmac),
    KEY idx_td_bulk_ledger_ticket_lookup (tenant_id, ticket_reference_hmac),
    CONSTRAINT fk_td_bulk_ledger_ticket_version FOREIGN KEY (version_id) REFERENCES td_bulk_ledger_versions (id),
    CONSTRAINT chk_td_bulk_ledger_ticket_values CHECK (ticket_quantity > 0 AND quantity_unit IN ('TICKET','ORDER_LINE') AND ticket_status IN ('ACTIVE','CANCELLED','REFUNDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE td_bulk_ledger_change_events (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_system VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    source_event_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    source_sequence BIGINT NOT NULL,
    source_changed_at DATETIME(6) NOT NULL,
    event_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_td_bulk_ledger_change_event (tenant_id, performance_id, source_system, source_event_id),
    UNIQUE KEY uk_td_bulk_ledger_change_sequence (tenant_id, performance_id, source_system, source_sequence),
    CONSTRAINT fk_td_bulk_ledger_change_version FOREIGN KEY (version_id) REFERENCES td_bulk_ledger_versions (id),
    CONSTRAINT chk_td_bulk_ledger_change_sequence CHECK (source_sequence >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Reserved for the subsequent publication issue. This change does not write this table.
CREATE TABLE td_bulk_ledger_publication_heads (
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    published_at DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id, performance_id),
    UNIQUE KEY uk_td_bulk_ledger_publication_version (version_id),
    CONSTRAINT fk_td_bulk_ledger_publication_performance FOREIGN KEY (tenant_id, event_id, performance_id) REFERENCES verified_performances (tenant_id, event_id, id),
    CONSTRAINT fk_td_bulk_ledger_publication_version FOREIGN KEY (version_id) REFERENCES td_bulk_ledger_versions (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
