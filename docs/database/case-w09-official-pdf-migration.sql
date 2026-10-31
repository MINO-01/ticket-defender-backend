-- Apply once after case-w08b-migration.sql. This adds a retryable issuance work record and a
-- separate immutable artifact record; it does not alter W08b ReportVersion state transitions.

CREATE TABLE official_pdf_issuances (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    case_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    approval_decision_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    approval_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    review_receipt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    report_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    report_revision BIGINT NOT NULL,
    report_content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    report_template_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    report_template_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_revision BIGINT NOT NULL,
    manifest_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    official_template_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    official_template_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    candidate_object_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_count INT NOT NULL,
    candidate_object_version VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NULL,
    candidate_pdf_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    candidate_pdf_size BIGINT NULL,
    last_failure_code VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NULL,
    requested_by_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    requested_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    issuance_version BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_official_pdf_issuance_decision (approval_decision_id),
    UNIQUE KEY uk_official_pdf_issuance_scope_id (tenant_id, event_id, case_id, id),
    -- Artifact의 승인 결정이 해당 Issuance를 예약한 결정과 같은지 복합 FK로 강제하기 위한 키다.
    UNIQUE KEY uk_official_pdf_issuance_scope_decision
        (tenant_id, event_id, case_id, id, approval_decision_id),
    KEY idx_official_pdf_issuance_scope_time (tenant_id, event_id, case_id, requested_at),
    CONSTRAINT fk_official_pdf_issuance_decision FOREIGN KEY
        (tenant_id, event_id, case_id, approval_decision_id)
        REFERENCES approval_decisions (tenant_id, event_id, case_id, id),
    CONSTRAINT fk_official_pdf_issuance_request FOREIGN KEY
        (tenant_id, event_id, case_id, approval_request_id)
        REFERENCES approval_requests (tenant_id, event_id, case_id, id),
    CONSTRAINT fk_official_pdf_issuance_receipt FOREIGN KEY
        (tenant_id, event_id, case_id, review_receipt_id)
        REFERENCES review_receipts (tenant_id, event_id, case_id, id),
    CONSTRAINT fk_official_pdf_issuance_report FOREIGN KEY
        (tenant_id, event_id, case_id, report_version_id)
        REFERENCES report_versions (tenant_id, event_id, case_id, id),
    CONSTRAINT fk_official_pdf_issuance_snapshot FOREIGN KEY
        (tenant_id, event_id, case_id, evidence_snapshot_id)
        REFERENCES evidence_snapshots (tenant_id, event_id, case_id, id),
    CONSTRAINT fk_official_pdf_issuance_actor FOREIGN KEY (tenant_id, requested_by_membership_id)
        REFERENCES memberships (tenant_id, id),
    CONSTRAINT chk_official_pdf_issuance_status CHECK (status IN
        ('RESERVED', 'RENDERING', 'OBJECT_STORED', 'GENERATED', 'FAILED', 'STALE')),
    CONSTRAINT chk_official_pdf_issuance_values CHECK
        (report_revision >= 1 AND evidence_revision >= 1 AND attempt_count >= 0
            AND issuance_version >= 0),
    CONSTRAINT chk_official_pdf_issuance_candidate CHECK
        ((status IN ('OBJECT_STORED', 'GENERATED')
            AND candidate_object_version IS NOT NULL AND candidate_pdf_sha256 IS NOT NULL
            AND candidate_pdf_size IS NOT NULL AND candidate_pdf_size > 0)
         OR (status NOT IN ('OBJECT_STORED', 'GENERATED'))),
    CONSTRAINT chk_official_pdf_issuance_completed CHECK
        ((status = 'GENERATED' AND completed_at IS NOT NULL)
         OR (status <> 'GENERATED' AND completed_at IS NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE official_pdf_issuance_attempts (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    issuance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_number INT NOT NULL,
    attempt_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    failure_code VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NULL,
    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_official_pdf_attempt_number (issuance_id, attempt_number),
    CONSTRAINT fk_official_pdf_attempt_issuance FOREIGN KEY (issuance_id)
        REFERENCES official_pdf_issuances (id),
    CONSTRAINT chk_official_pdf_attempt_kind CHECK (attempt_kind IN ('REQUEST', 'RETRY', 'RECOVERY')),
    CONSTRAINT chk_official_pdf_attempt_status CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'STALE')),
    CONSTRAINT chk_official_pdf_attempt_shape CHECK
        ((status = 'RUNNING' AND finished_at IS NULL AND failure_code IS NULL)
         OR (status = 'SUCCEEDED' AND finished_at IS NOT NULL AND failure_code IS NULL)
         OR (status IN ('FAILED', 'STALE') AND finished_at IS NOT NULL AND failure_code IS NOT NULL)),
    CONSTRAINT chk_official_pdf_attempt_number CHECK (attempt_number >= 1)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE official_pdf_artifacts (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    case_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    issuance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    approval_decision_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    approval_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    review_receipt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    report_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    report_revision BIGINT NOT NULL,
    report_content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    report_template_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    report_template_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_revision BIGINT NOT NULL,
    manifest_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    official_template_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    official_template_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_version VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    pdf_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    pdf_size BIGINT NOT NULL,
    generated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_official_pdf_artifact_issuance (issuance_id),
    UNIQUE KEY uk_official_pdf_artifact_decision (approval_decision_id),
    UNIQUE KEY uk_official_pdf_artifact_scope_id (tenant_id, event_id, case_id, id),
    KEY idx_official_pdf_artifact_scope_time (tenant_id, event_id, case_id, generated_at),
    CONSTRAINT fk_official_pdf_artifact_issuance FOREIGN KEY
        (tenant_id, event_id, case_id, issuance_id, approval_decision_id)
        REFERENCES official_pdf_issuances (tenant_id, event_id, case_id, id, approval_decision_id),
    CONSTRAINT fk_official_pdf_artifact_decision FOREIGN KEY
        (tenant_id, event_id, case_id, approval_decision_id)
        REFERENCES approval_decisions (tenant_id, event_id, case_id, id),
    CONSTRAINT chk_official_pdf_artifact_values CHECK
        (report_revision >= 1 AND evidence_revision >= 1 AND pdf_size > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE official_pdf_audit_events (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    case_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    issuance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    artifact_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    actor_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    detail_code VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_official_pdf_audit_scope_time (tenant_id, event_id, case_id, occurred_at),
    CONSTRAINT fk_official_pdf_audit_issuance FOREIGN KEY
        (tenant_id, event_id, case_id, issuance_id)
        REFERENCES official_pdf_issuances (tenant_id, event_id, case_id, id),
    CONSTRAINT fk_official_pdf_audit_artifact FOREIGN KEY
        (tenant_id, event_id, case_id, artifact_id)
        REFERENCES official_pdf_artifacts (tenant_id, event_id, case_id, id),
    CONSTRAINT fk_official_pdf_audit_actor FOREIGN KEY (tenant_id, actor_membership_id)
        REFERENCES memberships (tenant_id, id),
    CONSTRAINT chk_official_pdf_audit_event CHECK (event_type IN
        ('ISSUANCE_RESERVED', 'ISSUANCE_RETRIED', 'OBJECT_STORED', 'ISSUANCE_GENERATED',
         'ISSUANCE_FAILED', 'ISSUANCE_STALE', 'ARTIFACT_DOWNLOADED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
