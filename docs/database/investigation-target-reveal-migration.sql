-- PR 7: encrypted investigation references and access audit.
-- Apply after the current exp2 baseline and the bulk ledger / multi-event migrations.
-- This is additive. The application writes no ciphertext while the feature is disabled.
CREATE TABLE td_investigation_reference_ciphertexts (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    import_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_row_no BIGINT NOT NULL,
    ticket_reference_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reservation_reference_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_reference_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    reference_ciphertext VARBINARY(8192) NOT NULL,
    nonce BINARY(12) NOT NULL,
    encryption_key_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ledger_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_td_investigation_reference_import_row (import_id, source_row_no),
    KEY idx_td_investigation_reference_member (tenant_id, event_id, performance_id, ledger_version_id, account_reference_hmac),
    KEY idx_td_investigation_reference_ticket (ledger_version_id, ticket_reference_hmac),
    CONSTRAINT fk_td_investigation_reference_job FOREIGN KEY (import_id, tenant_id)
        REFERENCES td_bulk_import_jobs (id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT fk_td_investigation_reference_event FOREIGN KEY (tenant_id, event_id)
        REFERENCES events (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_td_investigation_reference_performance FOREIGN KEY (tenant_id, event_id, performance_id)
        REFERENCES verified_performances (tenant_id, event_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_td_investigation_reference_version_scope
        FOREIGN KEY (ledger_version_id, tenant_id, event_id, performance_id)
        REFERENCES td_bulk_ledger_versions (id, tenant_id, event_id, performance_id) ON DELETE CASCADE,
    CONSTRAINT chk_td_investigation_reference_values CHECK (source_row_no > 0 AND octet_length(reference_ciphertext) >= 16)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE td_investigation_reference_access_audit (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_subject VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    target_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    resource_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    action VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_count INT NOT NULL,
    reason VARCHAR(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    accessed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_td_investigation_access_audit_event (tenant_id, event_id, accessed_at),
    KEY idx_td_investigation_access_audit_actor (tenant_id, actor_subject, accessed_at),
    CONSTRAINT fk_td_investigation_access_audit_event FOREIGN KEY (tenant_id, event_id)
        REFERENCES events (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT chk_td_investigation_access_audit_values CHECK (
        target_type IN ('MULTI_EVENT', 'BULK_CLUSTER')
        AND action IN ('TARGETS', 'REVEAL', 'EXPORT')
        AND target_count >= 0
        AND char_length(trim(reason)) BETWEEN 1 AND 500
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
