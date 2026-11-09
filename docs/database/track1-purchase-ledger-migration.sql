-- Issue #81 Track 1 구매 수량 원장과 v2 분석 결과의 추가 전용 MySQL 8.0 마이그레이션입니다.
-- 승인된 격리 스키마에서만 실행하며 기존 Reservation, ReservationCandidate, run960 데이터는 변경하지 않습니다.
CREATE TABLE track1_purchase_ledger_batches (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_by_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    rows_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    input_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_version VARCHAR(128) NOT NULL,
    accepted_source_version VARCHAR(128) NULL,
    source_as_of DATETIME(6) NOT NULL,
    ledger_version INT NOT NULL,
    quantity_basis VARCHAR(128) NULL,
    reference_hmac_key_version VARCHAR(32) NOT NULL,
    synthetic_isolation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    complete_snapshot BIT(1) NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    input_row_count INT NOT NULL,
    accepted_row_count INT NOT NULL,
    rejected_row_count INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    row_version BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_t1_ledger_batch_idempotency (created_by_membership_id, event_id, idempotency_key_digest),
    UNIQUE KEY uk_t1_ledger_batch_source (tenant_id, event_id, performance_id, accepted_source_version),
    UNIQUE KEY uk_t1_ledger_batch_version (tenant_id, event_id, performance_id, ledger_version),
    KEY idx_t1_ledger_batch_history (tenant_id, event_id, created_at),
    CONSTRAINT fk_t1_ledger_batch_performance FOREIGN KEY (tenant_id, event_id, performance_id)
        REFERENCES verified_performances (tenant_id, event_id, id),
    CONSTRAINT fk_t1_ledger_batch_actor FOREIGN KEY (tenant_id, created_by_membership_id)
        REFERENCES memberships (tenant_id, id),
    CONSTRAINT chk_t1_ledger_batch_values CHECK (
        status IN ('ACCEPTED', 'REJECTED')
        AND input_type IN ('INDIVIDUAL_TICKET', 'ACCOUNT_LIMIT_SNAPSHOT')
        AND ledger_version >= 1 AND input_row_count >= 0 AND accepted_row_count >= 0 AND rejected_row_count >= 0
        AND (status <> 'ACCEPTED' OR (input_type = 'INDIVIDUAL_TICKET' AND accepted_source_version = source_version
            AND rows_digest IS NOT NULL AND quantity_basis IS NOT NULL
            AND accepted_row_count >= 1 AND accepted_row_count = input_row_count AND rejected_row_count = 0))
        AND (status <> 'REJECTED' OR (accepted_source_version IS NULL AND accepted_row_count = 0))
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE track1_purchase_ledger_rows (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_row_index INT NOT NULL,
    account_ref_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reservation_ref_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ticket_ref_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    purchased_at DATETIME(6) NOT NULL,
    ticket_quantity INT NOT NULL,
    quantity_unit VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ticket_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payment_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    address_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    device_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ip_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    source_version VARCHAR(128) NOT NULL,
    source_as_of DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_t1_ledger_row_ticket (batch_id, ticket_ref_hmac),
    UNIQUE KEY uk_t1_ledger_row_index (batch_id, source_row_index),
    KEY idx_t1_ledger_row_batch_account (batch_id, account_ref_hmac),
    CONSTRAINT fk_t1_ledger_row_batch FOREIGN KEY (batch_id) REFERENCES track1_purchase_ledger_batches (id),
    CONSTRAINT chk_t1_ledger_row_values CHECK (
        source_row_index >= 0 AND ticket_quantity >= 1 AND purchased_at <= source_as_of
        AND quantity_unit IN ('TICKET', 'ORDER_LINE') AND (quantity_unit <> 'TICKET' OR ticket_quantity = 1)
        AND ticket_status IN ('ACTIVE', 'CANCELLED', 'REFUNDED', 'UNKNOWN')
        AND (payment_hmac IS NOT NULL OR address_hmac IS NOT NULL OR device_hmac IS NOT NULL OR ip_hmac IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE track1_purchase_ledger_errors (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    row_index INT NULL,
    code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (id),
    KEY idx_t1_ledger_error_batch (batch_id, row_index),
    CONSTRAINT fk_t1_ledger_error_batch FOREIGN KEY (batch_id) REFERENCES track1_purchase_ledger_batches (id),
    CONSTRAINT chk_t1_ledger_error_index CHECK (row_index IS NULL OR row_index >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE track1_purchase_limit_runs (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ledger_batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    active_ledger_batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_by_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    analysis_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ledger_source_version VARCHAR(128) NOT NULL,
    policy_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    policy_version VARCHAR(128) NULL,
    execution_status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evaluation_status VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    evaluation_reason VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    algorithm VARCHAR(32) NULL,
    algorithm_version VARCHAR(160) NULL,
    cluster_count INT NULL,
    netted_excluded_row_count INT NOT NULL,
    analyzed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    row_version BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_t1_limit_run_idempotency (created_by_membership_id, event_id, idempotency_key_digest),
    UNIQUE KEY uk_t1_limit_run_active_batch (active_ledger_batch_id),
    UNIQUE KEY uk_t1_limit_run_request (analysis_request_id),
    KEY idx_t1_limit_run_scope_time (tenant_id, event_id, created_at),
    CONSTRAINT fk_t1_limit_run_batch FOREIGN KEY (ledger_batch_id) REFERENCES track1_purchase_ledger_batches (id),
    CONSTRAINT fk_t1_limit_run_performance FOREIGN KEY (tenant_id, event_id, performance_id)
        REFERENCES verified_performances (tenant_id, event_id, id),
    CONSTRAINT fk_t1_limit_run_actor FOREIGN KEY (tenant_id, created_by_membership_id)
        REFERENCES memberships (tenant_id, id),
    CONSTRAINT fk_t1_limit_run_policy FOREIGN KEY (policy_id) REFERENCES purchase_limit_policies (id),
    CONSTRAINT chk_t1_limit_run_values CHECK (
        execution_status IN ('IN_PROGRESS', 'SUCCEEDED', 'INPUT_REJECTED', 'ANALYZER_UNAVAILABLE', 'ANALYZER_FAILED', 'INTERRUPTED')
        AND (cluster_count IS NULL OR cluster_count >= 0) AND netted_excluded_row_count >= 0
        AND ((execution_status = 'IN_PROGRESS') = (active_ledger_batch_id IS NOT NULL))
        AND (execution_status <> 'SUCCEEDED' OR (evaluation_status IS NOT NULL AND cluster_count IS NOT NULL AND error_code IS NULL))
        AND (execution_status IN ('IN_PROGRESS', 'SUCCEEDED') OR (error_code IS NOT NULL AND evaluation_status IS NULL))
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE track1_purchase_limit_findings (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_ref_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    effective_ticket_quantity INT NOT NULL,
    max_tickets_per_account INT NOT NULL,
    policy_version VARCHAR(128) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_t1_limit_finding_run_account (run_id, account_ref_hmac),
    CONSTRAINT fk_t1_limit_finding_run FOREIGN KEY (run_id) REFERENCES track1_purchase_limit_runs (id),
    CONSTRAINT chk_t1_limit_finding_values CHECK (effective_ticket_quantity >= 1 AND max_tickets_per_account >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE track1_purchase_limit_finding_reservations (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    finding_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reservation_ref_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    effective_ticket_quantity INT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_t1_limit_finding_reservation (finding_id, reservation_ref_hmac),
    CONSTRAINT fk_t1_limit_finding_reservation FOREIGN KEY (finding_id) REFERENCES track1_purchase_limit_findings (id),
    CONSTRAINT chk_t1_limit_finding_reservation CHECK (effective_ticket_quantity >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE track1_purchase_limit_run_clusters (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    cluster_id VARCHAR(128) NOT NULL,
    risk_score DOUBLE NOT NULL,
    member_count INT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_t1_limit_run_cluster (run_id, cluster_id),
    CONSTRAINT fk_t1_limit_run_cluster FOREIGN KEY (run_id) REFERENCES track1_purchase_limit_runs (id),
    CONSTRAINT chk_t1_limit_run_cluster CHECK (risk_score >= 0 AND risk_score <= 1 AND member_count >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
