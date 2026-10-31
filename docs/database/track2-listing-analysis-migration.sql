-- case-w09-official-pdf-migration.sql 적용 후 한 번 적용합니다. 기존 팬 제보와 증거 행은 변경하지 않습니다.
-- 원본 이미지는 저장하지 않고 분석 입력의 출처와 Python Listing Context 응답의 관찰 결과만 기록합니다.

CREATE TABLE listing_context_analyses (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fan_report_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_by_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    -- IN_PROGRESS 동안만 evidence_id와 같은 값을 가져 증거별 동시 실행을 1건으로 제한합니다.
    active_evidence_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    object_key_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_marker VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NULL,
    expected_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    verified_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    expected_byte_size BIGINT NOT NULL,
    verified_byte_size BIGINT NULL,
    media_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    execution_status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    analysis_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    analyzer_http_status INT NULL,
    analyzer_error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    model_name VARCHAR(128) NULL,
    model_version VARCHAR(128) NULL,
    analyzed_at DATETIME(6) NULL,
    review_required BIT(1) NULL,
    response_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    candidate_match_status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    candidate_match_reason VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    lease_expires_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    row_version BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_listing_analyses_request (request_id),
    UNIQUE KEY uk_listing_analyses_active_evidence (active_evidence_id),
    UNIQUE KEY uk_listing_analyses_idempotency (created_by_membership_id, evidence_id, idempotency_key_digest),
    KEY idx_listing_analyses_evidence_time (evidence_id, created_at),
    KEY idx_listing_analyses_scope (tenant_id, event_id, fan_report_id),
    CONSTRAINT fk_listing_analyses_report FOREIGN KEY (tenant_id, event_id, fan_report_id)
        REFERENCES fan_reports (tenant_id, event_id, id),
    CONSTRAINT fk_listing_analyses_asset FOREIGN KEY (fan_report_id, evidence_id)
        REFERENCES evidence_assets (fan_report_id, id),
    CONSTRAINT fk_listing_analyses_actor FOREIGN KEY (tenant_id, created_by_membership_id)
        REFERENCES memberships (tenant_id, id),
    CONSTRAINT chk_listing_analyses_execution CHECK (execution_status IN
        ('IN_PROGRESS', 'SUCCEEDED', 'INPUT_REJECTED', 'STORAGE_FAILED', 'ANALYZER_FAILED', 'ABANDONED')),
    CONSTRAINT chk_listing_analyses_status CHECK (analysis_status IS NULL OR analysis_status IN
        ('COMPLETED', 'PARTIAL', 'UNVERIFIABLE', 'FAILED')),
    -- MySQL CHECK는 NULL 결과를 위반으로 보지 않으므로 비교 대상 컬럼의 NOT NULL을 함께 명시합니다.
    CONSTRAINT chk_listing_analyses_active CHECK (
        (execution_status = 'IN_PROGRESS' AND active_evidence_id IS NOT NULL AND active_evidence_id = evidence_id AND analysis_status IS NULL
            AND completed_at IS NULL)
        OR (execution_status <> 'IN_PROGRESS' AND active_evidence_id IS NULL AND completed_at IS NOT NULL)),
    -- 성공은 계약의 200 상태만 허용하고, 실행 실패는 반드시 FAILED와 오류 코드를 가집니다.
    CONSTRAINT chk_listing_analyses_outcome CHECK (
        execution_status = 'IN_PROGRESS'
        OR (execution_status = 'SUCCEEDED' AND analysis_status IS NOT NULL
            AND analysis_status IN ('COMPLETED', 'PARTIAL', 'UNVERIFIABLE')
            AND error_code IS NULL AND model_name IS NOT NULL AND analyzed_at IS NOT NULL
            AND review_required IS NOT NULL AND review_required = b'1' AND response_sha256 IS NOT NULL
            AND verified_sha256 IS NOT NULL AND verified_sha256 = expected_sha256
            AND verified_byte_size IS NOT NULL AND verified_byte_size = expected_byte_size)
        OR (execution_status NOT IN ('IN_PROGRESS', 'SUCCEEDED') AND analysis_status IS NOT NULL
            AND analysis_status = 'FAILED'
            AND error_code IS NOT NULL AND model_name IS NULL AND analyzed_at IS NULL)),
    CONSTRAINT chk_listing_analyses_candidate CHECK (
        (execution_status = 'IN_PROGRESS' AND candidate_match_status IS NULL AND candidate_match_reason IS NULL)
        OR (execution_status <> 'IN_PROGRESS' AND candidate_match_status IS NOT NULL
            AND candidate_match_status = 'NOT_LINKED' AND candidate_match_reason IS NOT NULL
            AND candidate_match_reason IN ('PERFORMANCE_REF_UNVERIFIED', 'SEAT_NOT_FOUND',
                'MATCH_SCORE_UNAVAILABLE', 'ANALYSIS_NOT_SUCCEEDED'))),
    CONSTRAINT chk_listing_analyses_size CHECK (expected_byte_size > 0
        AND (verified_byte_size IS NULL OR verified_byte_size >= 0))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE listing_context_observations (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    analysis_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    field_name VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    value_text VARCHAR(256) NULL,
    price_raw VARCHAR(64) NULL,
    price_amount VARCHAR(15) CHARACTER SET ascii COLLATE ascii_bin NULL,
    evidence_quote VARCHAR(512) NULL,
    unknown_reason VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    candidate_1 VARCHAR(128) NULL,
    candidate_2 VARCHAR(128) NULL,
    candidate_3 VARCHAR(128) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_listing_observations_field (analysis_id, field_name),
    CONSTRAINT fk_listing_observations_analysis FOREIGN KEY (analysis_id) REFERENCES listing_context_analyses (id),
    CONSTRAINT chk_listing_observations_field CHECK (field_name IN ('eventName', 'performanceAt',
        'platformDisplayName', 'askingPrice', 'currency', 'saleText', 'zone', 'row', 'seat')),
    -- FOUND, UNKNOWN, AMBIGUOUS의 계약 불변식을 DB에서도 유지합니다.
    CONSTRAINT chk_listing_observations_state CHECK (
        (state = 'FOUND' AND evidence_quote IS NOT NULL AND unknown_reason IS NULL AND candidate_1 IS NULL
            AND ((field_name = 'askingPrice' AND value_text IS NULL AND price_raw IS NOT NULL
                    AND price_amount IS NOT NULL)
                OR (field_name <> 'askingPrice' AND value_text IS NOT NULL AND price_raw IS NULL
                    AND price_amount IS NULL)))
        OR (state = 'UNKNOWN' AND value_text IS NULL AND price_raw IS NULL AND price_amount IS NULL
            AND evidence_quote IS NULL AND unknown_reason IS NOT NULL
            AND unknown_reason IN ('ABSENT', 'UNREADABLE', 'NOT_CONFIRMED')
            AND candidate_1 IS NULL)
        OR (state = 'AMBIGUOUS' AND value_text IS NULL AND price_raw IS NULL AND price_amount IS NULL
            AND evidence_quote IS NOT NULL AND unknown_reason IS NULL AND candidate_1 IS NOT NULL
            AND candidate_2 IS NOT NULL)),
    CONSTRAINT chk_listing_observations_candidates CHECK (
        (candidate_2 IS NULL OR candidate_1 IS NOT NULL) AND (candidate_3 IS NULL OR candidate_2 IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
