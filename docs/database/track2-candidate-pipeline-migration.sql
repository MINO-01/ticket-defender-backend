-- Issue #68 Track 2 회차 검증·합성 예매 적재·후보 대조 파이프라인입니다.
-- 적용 순서: admin-membership-event-migration.sql, fan-report-intake-migration.sql,
-- track2-listing-analysis-migration.sql 다음에 MySQL 8.0.16 이상에서 실행합니다.
-- 기존 reservation, ticket_audit, listing_context_analyses의 과거 행을 수정하거나 소급 연결하지 않습니다.

CREATE TABLE verified_performances (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    performance_ref VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    verification_basis VARCHAR(512) NOT NULL,
    source_version VARCHAR(128) NOT NULL,
    verified_by_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    verified_at DATETIME(6) NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision INT NOT NULL,
    withdrawn_by_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    withdrawn_at DATETIME(6) NULL,
    withdrawal_reason VARCHAR(512) NULL,
    row_version BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_verified_performances_scope_ref (tenant_id, event_id, performance_ref),
    UNIQUE KEY uk_verified_performances_scope_id (tenant_id, event_id, id),
    KEY idx_verified_performances_scope_status (tenant_id, event_id, status),
    CONSTRAINT fk_verified_performance_event FOREIGN KEY (tenant_id, event_id) REFERENCES events (tenant_id, id),
    CONSTRAINT fk_verified_performance_actor FOREIGN KEY (tenant_id, verified_by_membership_id)
        REFERENCES memberships (tenant_id, id),
    CONSTRAINT fk_verified_performance_withdrawn_actor FOREIGN KEY (tenant_id, withdrawn_by_membership_id)
        REFERENCES memberships (tenant_id, id),
    CONSTRAINT chk_verified_performance_status CHECK (
        (status = 'ACTIVE' AND withdrawn_by_membership_id IS NULL AND withdrawn_at IS NULL AND withdrawal_reason IS NULL)
        OR (status = 'WITHDRAWN' AND withdrawn_by_membership_id IS NOT NULL AND withdrawn_at IS NOT NULL
            AND withdrawal_reason IS NOT NULL)),
    CONSTRAINT chk_verified_performance_values CHECK (
        CHAR_LENGTH(TRIM(performance_ref)) > 0 AND CHAR_LENGTH(TRIM(verification_basis)) > 0
        AND CHAR_LENGTH(TRIM(source_version)) > 0 AND revision >= 1)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE performance_verification_audits (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    performance_ref VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    action VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision INT NOT NULL,
    verification_basis VARCHAR(512) NOT NULL,
    source_version VARCHAR(128) NOT NULL,
    actor_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_performance_verification_audit_revision (performance_id, revision),
    KEY idx_performance_verification_audit_scope (tenant_id, event_id, occurred_at),
    CONSTRAINT fk_performance_verification_audit_performance FOREIGN KEY (performance_id)
        REFERENCES verified_performances (id),
    CONSTRAINT fk_performance_verification_audit_actor FOREIGN KEY (tenant_id, actor_membership_id)
        REFERENCES memberships (tenant_id, id),
    CONSTRAINT chk_performance_verification_audit_action CHECK (action IN ('VERIFIED', 'REVISED', 'WITHDRAWN')),
    CONSTRAINT chk_performance_verification_audit_revision CHECK (revision >= 1)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE fan_report_performance_links (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fan_report_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    performance_ref VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    performance_revision INT NOT NULL,
    link_revision INT NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    active_fan_report_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    reason VARCHAR(512) NOT NULL,
    linked_by_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    ended_by_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ended_at DATETIME(6) NULL,
    row_version BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fan_report_performance_link_active (active_fan_report_id),
    UNIQUE KEY uk_fan_report_performance_link_scope_id (tenant_id, event_id, id),
    KEY idx_fan_report_performance_links_report (fan_report_id, created_at),
    CONSTRAINT fk_fan_report_performance_link_report FOREIGN KEY (tenant_id, event_id, fan_report_id)
        REFERENCES fan_reports (tenant_id, event_id, id),
    CONSTRAINT fk_fan_report_performance_link_performance FOREIGN KEY (tenant_id, event_id, performance_id)
        REFERENCES verified_performances (tenant_id, event_id, id),
    CONSTRAINT fk_fan_report_performance_link_actor FOREIGN KEY (tenant_id, linked_by_membership_id)
        REFERENCES memberships (tenant_id, id),
    CONSTRAINT fk_fan_report_performance_link_ended_actor FOREIGN KEY (tenant_id, ended_by_membership_id)
        REFERENCES memberships (tenant_id, id),
    CONSTRAINT chk_fan_report_performance_link_status CHECK (
        (status = 'ACTIVE' AND active_fan_report_id = fan_report_id AND ended_by_membership_id IS NULL AND ended_at IS NULL)
        OR (status IN ('REPLACED', 'WITHDRAWN') AND active_fan_report_id IS NULL
            AND ended_by_membership_id IS NOT NULL AND ended_at IS NOT NULL)),
    CONSTRAINT chk_fan_report_performance_link_values CHECK (
        link_revision >= 1 AND performance_revision >= 1 AND CHAR_LENGTH(TRIM(reason)) > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE performance_link_audits (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    link_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fan_report_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    link_revision INT NOT NULL,
    action VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(512) NOT NULL,
    actor_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_performance_link_audit_idempotency (actor_membership_id, fan_report_id, idempotency_key_digest),
    KEY idx_performance_link_audit_link (link_id, occurred_at),
    CONSTRAINT fk_performance_link_audit_link FOREIGN KEY (link_id) REFERENCES fan_report_performance_links (id),
    CONSTRAINT fk_performance_link_audit_actor FOREIGN KEY (tenant_id, actor_membership_id)
        REFERENCES memberships (tenant_id, id),
    CONSTRAINT chk_performance_link_audit_action CHECK (action IN ('LINKED', 'REPLACED', 'WITHDRAWN')),
    CONSTRAINT chk_performance_link_audit_digests CHECK (
        idempotency_key_digest REGEXP '^[0-9a-f]{64}$' AND request_digest REGEXP '^[0-9a-f]{64}$')
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 공개 API가 이 등록을 생성하지 않습니다. 격리 환경 fixture/운영자가 서버 설정과 함께 준비해야 합니다.
CREATE TABLE synthetic_reservation_import_tenants (
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    isolation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    registered_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    PRIMARY KEY (tenant_id),
    CONSTRAINT fk_synthetic_reservation_import_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT chk_synthetic_reservation_import_isolation CHECK (
        isolation_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE reservation_import_batches (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    performance_ref VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    created_by_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_version VARCHAR(128) NOT NULL,
    reference_hmac_key_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    synthetic_isolation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    complete_snapshot BIT(1) NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    input_row_count INT NOT NULL,
    accepted_row_count INT NOT NULL,
    rejected_row_count INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NOT NULL,
    row_version BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_reservation_import_batch_idempotency (created_by_membership_id, event_id, idempotency_key_digest),
    UNIQUE KEY uk_reservation_import_batch_source_version (tenant_id, event_id, performance_id, source_version),
    UNIQUE KEY uk_reservation_import_batch_scope_id (tenant_id, event_id, id),
    KEY idx_reservation_import_batches_scope_time (tenant_id, event_id, created_at),
    CONSTRAINT fk_reservation_import_batch_performance FOREIGN KEY (tenant_id, event_id, performance_id)
        REFERENCES verified_performances (tenant_id, event_id, id),
    CONSTRAINT fk_reservation_import_batch_actor FOREIGN KEY (tenant_id, created_by_membership_id)
        REFERENCES memberships (tenant_id, id),
    CONSTRAINT chk_reservation_import_batch_status CHECK (status IN ('ACCEPTED', 'REJECTED')),
    CONSTRAINT chk_reservation_import_batch_counts CHECK (
        input_row_count >= 0 AND accepted_row_count >= 0 AND rejected_row_count >= 0
        AND ((status = 'ACCEPTED' AND complete_snapshot = b'1' AND accepted_row_count = input_row_count
                AND rejected_row_count = 0)
            OR (status = 'REJECTED' AND accepted_row_count = 0 AND rejected_row_count > 0))),
    CONSTRAINT chk_reservation_import_batch_digests CHECK (
        idempotency_key_digest REGEXP '^[0-9a-f]{64}$' AND request_digest REGEXP '^[0-9a-f]{64}$'
        AND CHAR_LENGTH(TRIM(source_version)) > 0 AND CHAR_LENGTH(TRIM(reference_hmac_key_version)) > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE reservation_import_errors (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    row_index INT NOT NULL,
    code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_reservation_import_error (batch_id, row_index, code),
    CONSTRAINT fk_reservation_import_error_batch FOREIGN KEY (batch_id) REFERENCES reservation_import_batches (id),
    CONSTRAINT chk_reservation_import_error_row CHECK (row_index >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE reservation_candidates (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_row_index INT NOT NULL,
    reservation_ref_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reference_hmac_key_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    zone VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    row_num VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    seat_num VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_reservation_candidate_reference (batch_id, reservation_ref_hmac),
    KEY idx_reservation_candidate_seat (batch_id, zone, row_num, seat_num),
    CONSTRAINT fk_reservation_candidate_batch FOREIGN KEY (batch_id) REFERENCES reservation_import_batches (id),
    CONSTRAINT chk_reservation_candidate_values CHECK (
        source_row_index >= 0 AND reservation_ref_hmac REGEXP '^[0-9a-f]{64}$'
        AND CHAR_LENGTH(TRIM(reference_hmac_key_version)) > 0 AND CHAR_LENGTH(TRIM(zone)) > 0
        AND CHAR_LENGTH(TRIM(row_num)) > 0 AND CHAR_LENGTH(TRIM(seat_num)) > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE reservation_data_sources (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    current_batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    current_source_version VARCHAR(128) NOT NULL,
    complete_snapshot BIT(1) NOT NULL,
    source_revision INT NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    row_version BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_reservation_data_source_scope (tenant_id, event_id, performance_id),
    UNIQUE KEY uk_reservation_data_source_scope_id (tenant_id, event_id, id),
    KEY idx_reservation_data_source_current (current_batch_id),
    CONSTRAINT fk_reservation_data_source_performance FOREIGN KEY (tenant_id, event_id, performance_id)
        REFERENCES verified_performances (tenant_id, event_id, id),
    CONSTRAINT fk_reservation_data_source_batch FOREIGN KEY (current_batch_id) REFERENCES reservation_import_batches (id),
    CONSTRAINT chk_reservation_data_source_complete CHECK (
        complete_snapshot = b'1' AND source_revision >= 1 AND CHAR_LENGTH(TRIM(current_source_version)) > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 후보 실행에서 tenant/event와 analysis_id를 동시에 FK로 검증하기 위한 보조 unique key입니다.
ALTER TABLE listing_context_analyses
    ADD UNIQUE KEY uk_listing_analyses_scope_id (tenant_id, event_id, id);

CREATE TABLE listing_candidate_match_runs (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fan_report_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    analysis_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_by_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    active_analysis_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_expires_at DATETIME(6) NULL,
    performance_link_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    performance_link_revision INT NULL,
    verified_performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    verified_performance_revision INT NULL,
    reservation_source_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    reservation_source_revision INT NULL,
    reservation_batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    reservation_source_version VARCHAR(128) NULL,
    evidence_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    analysis_response_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    analysis_row_version BIGINT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    candidate_count INT NULL,
    candidate_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    row_version BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_listing_candidate_run_idempotency (created_by_membership_id, analysis_id, idempotency_key_digest),
    UNIQUE KEY uk_listing_candidate_run_active_analysis (active_analysis_id),
    UNIQUE KEY uk_listing_candidate_run_scope_id (tenant_id, event_id, id),
    KEY idx_listing_candidate_runs_analysis_time (analysis_id, created_at),
    CONSTRAINT fk_listing_candidate_run_analysis FOREIGN KEY (tenant_id, event_id, analysis_id)
        REFERENCES listing_context_analyses (tenant_id, event_id, id),
    CONSTRAINT fk_listing_candidate_run_report FOREIGN KEY (tenant_id, event_id, fan_report_id)
        REFERENCES fan_reports (tenant_id, event_id, id),
    CONSTRAINT fk_listing_candidate_run_asset FOREIGN KEY (fan_report_id, evidence_id)
        REFERENCES evidence_assets (fan_report_id, id),
    CONSTRAINT fk_listing_candidate_run_actor FOREIGN KEY (tenant_id, created_by_membership_id)
        REFERENCES memberships (tenant_id, id),
    CONSTRAINT fk_listing_candidate_run_link FOREIGN KEY (performance_link_id)
        REFERENCES fan_report_performance_links (id),
    CONSTRAINT fk_listing_candidate_run_performance FOREIGN KEY (verified_performance_id)
        REFERENCES verified_performances (id),
    CONSTRAINT fk_listing_candidate_run_source FOREIGN KEY (reservation_source_id)
        REFERENCES reservation_data_sources (id),
    CONSTRAINT fk_listing_candidate_run_batch FOREIGN KEY (reservation_batch_id)
        REFERENCES reservation_import_batches (id),
    CONSTRAINT fk_listing_candidate_run_candidate FOREIGN KEY (candidate_id) REFERENCES reservation_candidates (id),
    CONSTRAINT chk_listing_candidate_run_status CHECK (status IN
        ('IN_PROGRESS', 'MATCH_CANDIDATE', 'NO_MATCH', 'AMBIGUOUS', 'UNVERIFIABLE', 'LOOKUP_FAILED', 'ANALYSIS_FAILED')),
    CONSTRAINT chk_listing_candidate_run_lifecycle CHECK (
        (status = 'IN_PROGRESS' AND active_analysis_id = analysis_id AND reason = 'LOOKUP_STARTED'
            AND completed_at IS NULL AND lease_expires_at IS NOT NULL)
        OR (status <> 'IN_PROGRESS' AND active_analysis_id IS NULL AND completed_at IS NOT NULL
            AND lease_expires_at IS NULL)),
    CONSTRAINT chk_listing_candidate_run_candidate CHECK (
        (status = 'MATCH_CANDIDATE' AND candidate_count = 1 AND candidate_id IS NOT NULL)
        OR (status = 'NO_MATCH' AND candidate_count = 0 AND candidate_id IS NULL)
        OR (status = 'AMBIGUOUS' AND candidate_count >= 2 AND candidate_id IS NULL)
        OR (status IN ('UNVERIFIABLE', 'LOOKUP_FAILED', 'ANALYSIS_FAILED', 'IN_PROGRESS') AND candidate_id IS NULL)),
    CONSTRAINT chk_listing_candidate_run_digests CHECK (
        idempotency_key_digest REGEXP '^[0-9a-f]{64}$' AND request_digest REGEXP '^[0-9a-f]{64}$'
        AND (evidence_sha256 IS NULL OR evidence_sha256 REGEXP '^[0-9a-f]{64}$')
        AND (analysis_response_sha256 IS NULL OR analysis_response_sha256 REGEXP '^[0-9a-f]{64}$'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
