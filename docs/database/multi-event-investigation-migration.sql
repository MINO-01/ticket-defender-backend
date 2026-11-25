-- MEI-01: separate, additive storage for fixed multi-event investigation runs.
-- Apply after BL-07 and bulk-ledger-score-v2-exp2-signal-migration.sql.
CREATE TABLE IF NOT EXISTS td_multi_event_investigation_runs (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    requester_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ledger_version_set_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    supplier_namespace VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    hmac_purpose VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    hmac_key_version VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    score_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    projection_version VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    analysis_algorithm_version VARCHAR(256) CHARACTER SET ascii COLLATE ascii_bin NULL,
    analysis_as_of DATETIME(6) NOT NULL,
    analysis_start_at DATETIME(6) NOT NULL,
    analysis_end_at DATETIME(6) NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    failure_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_owner VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(6) NULL,
    fence_token BIGINT NOT NULL DEFAULT 0,
    attempt_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mei_request (tenant_id,requester_membership_id,idempotency_key_digest),
    KEY idx_mei_tenant_created (tenant_id,created_at),
    KEY idx_mei_claim (status,lease_until,created_at),
    CONSTRAINT fk_mei_requester FOREIGN KEY (tenant_id,requester_membership_id) REFERENCES memberships (tenant_id,id),
    CONSTRAINT chk_mei_run_state CHECK (
        hmac_purpose = 'TRACK1_CONNECTION'
        AND score_version = 'score-v2-exp2-multi-event-recurrence'
        AND projection_version = 'projection-v2.2-exp2-ipwindow-softhub'
        AND analysis_end_at > analysis_start_at AND analysis_end_at <= analysis_as_of
        AND status IN ('PENDING','RUNNING','RETRYABLE','FAILED','BLOCKED','COMPLETED')
        AND fence_token >= 0 AND attempt_count >= 0 AND row_version >= 0
        AND ((status = 'RUNNING') = (lease_owner IS NOT NULL AND lease_until IS NOT NULL))
        AND ((status = 'COMPLETED') = (completed_at IS NOT NULL))
        AND ((status = 'COMPLETED') = (analysis_algorithm_version IS NOT NULL))
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS td_multi_event_investigation_scopes (
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ledger_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    external_event_ref VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    source_batch_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    PRIMARY KEY (run_id,performance_id),
    UNIQUE KEY uk_mei_scope_version (run_id,ledger_version_id),
    KEY idx_mei_scope_event (tenant_id,event_id,performance_id),
    CONSTRAINT fk_mei_scope_run FOREIGN KEY (run_id) REFERENCES td_multi_event_investigation_runs (id),
    CONSTRAINT fk_mei_scope_performance FOREIGN KEY (tenant_id,event_id,performance_id) REFERENCES verified_performances (tenant_id,event_id,id),
    CONSTRAINT fk_mei_scope_version FOREIGN KEY (ledger_version_id) REFERENCES td_bulk_ledger_versions (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS td_multi_event_investigation_candidates (
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    candidate_id CHAR(70) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    priority_band VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    investigation_score DOUBLE NOT NULL,
    baseline_max_score DOUBLE NULL,
    distinct_account_count INT NOT NULL,
    distinct_event_count INT NOT NULL,
    distinct_performance_count INT NOT NULL,
    verified_relationship_count INT NOT NULL,
    score_breakdown_json JSON NOT NULL,
    available_signals_json JSON NOT NULL,
    excluded_signals_json JSON NOT NULL,
    unavailable_signals_json JSON NOT NULL,
    normal_sharing_json JSON NOT NULL,
    PRIMARY KEY (run_id,candidate_id),
    KEY idx_mei_candidates_rank (run_id,investigation_score,candidate_id),
    CONSTRAINT fk_mei_candidate_run FOREIGN KEY (run_id) REFERENCES td_multi_event_investigation_runs (id),
    CONSTRAINT chk_mei_candidate_values CHECK (
        priority_band IN ('HIGH','MEDIUM','LOW') AND investigation_score >= 0 AND investigation_score <= 1
        AND (baseline_max_score IS NULL OR (baseline_max_score >= 0 AND baseline_max_score <= 1))
        AND distinct_account_count >= 2 AND distinct_event_count >= 2 AND distinct_performance_count >= 2
        AND verified_relationship_count >= 1
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS td_multi_event_investigation_members (
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    candidate_id CHAR(70) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_reference_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (run_id,candidate_id,account_reference_hmac),
    CONSTRAINT fk_mei_member_candidate FOREIGN KEY (run_id,candidate_id)
        REFERENCES td_multi_event_investigation_candidates (run_id,candidate_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS td_multi_event_investigation_relationships (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    candidate_id CHAR(70) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    signal_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_count INT NOT NULL,
    source_scope_count INT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mei_relationship (run_id,candidate_id,signal_type,account_count,source_scope_count),
    KEY idx_mei_relationship_candidate (run_id,candidate_id),
    CONSTRAINT fk_mei_relationship_candidate FOREIGN KEY (run_id,candidate_id)
        REFERENCES td_multi_event_investigation_candidates (run_id,candidate_id),
    CONSTRAINT chk_mei_relationship_values CHECK (account_count >= 2 AND source_scope_count >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS td_multi_event_investigation_relationship_sources (
    relationship_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (relationship_id,performance_id),
    CONSTRAINT fk_mei_relationship_source_relationship FOREIGN KEY (relationship_id)
        REFERENCES td_multi_event_investigation_relationships (id),
    CONSTRAINT fk_mei_relationship_source_scope FOREIGN KEY (run_id,performance_id)
        REFERENCES td_multi_event_investigation_scopes (run_id,performance_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS td_multi_event_investigation_relationship_members (
    relationship_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    candidate_id CHAR(70) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_reference_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (relationship_id,account_reference_hmac),
    KEY idx_mei_relationship_member_candidate (run_id,candidate_id,account_reference_hmac),
    CONSTRAINT fk_mei_relationship_member_relationship FOREIGN KEY (relationship_id)
        REFERENCES td_multi_event_investigation_relationships (id),
    CONSTRAINT fk_mei_relationship_member_candidate FOREIGN KEY (run_id,candidate_id,account_reference_hmac)
        REFERENCES td_multi_event_investigation_members (run_id,candidate_id,account_reference_hmac)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
