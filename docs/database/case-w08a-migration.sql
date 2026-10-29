-- Apply once after case-w07-migration.sql. Existing W02-W07 rows remain unchanged.

ALTER TABLE report_versions
    ADD COLUMN template_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER template_version,
    ADD UNIQUE KEY uk_report_versions_scope_id (tenant_id, event_id, case_id, id),
    DROP CHECK chk_report_versions_status,
    MODIFY status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD CONSTRAINT chk_report_versions_status CHECK (status IN
        ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'INVALIDATED'));

ALTER TABLE cases
    ADD COLUMN current_report_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER evidence_revision;

CREATE TABLE approval_requests (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    case_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    report_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    report_revision BIGINT NOT NULL,
    report_content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    report_template_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    report_template_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_revision BIGINT NOT NULL,
    manifest_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    requested_by_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    requested_at DATETIME(6) NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    case_version_at_submit BIGINT NOT NULL,
    active_case_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    request_version BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_approval_request_report (report_version_id),
    UNIQUE KEY uk_approval_request_active_case (tenant_id, active_case_id),
    UNIQUE KEY uk_approval_request_scope_id (tenant_id, event_id, case_id, id),
    KEY idx_approval_request_scope_time (tenant_id, event_id, case_id, requested_at),
    CONSTRAINT fk_approval_request_case FOREIGN KEY (tenant_id, event_id, case_id)
        REFERENCES cases (tenant_id, event_id, id),
    CONSTRAINT fk_approval_request_report FOREIGN KEY
        (tenant_id, event_id, case_id, report_version_id)
        REFERENCES report_versions (tenant_id, event_id, case_id, id),
    CONSTRAINT fk_approval_request_snapshot FOREIGN KEY
        (tenant_id, event_id, case_id, evidence_snapshot_id)
        REFERENCES evidence_snapshots (tenant_id, event_id, case_id, id),
    CONSTRAINT fk_approval_request_actor FOREIGN KEY (tenant_id, requested_by_membership_id)
        REFERENCES memberships (tenant_id, id),
    CONSTRAINT chk_approval_request_status CHECK (status IN
        ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'INVALIDATED')),
    CONSTRAINT chk_approval_request_versions CHECK
        (report_revision >= 1 AND evidence_revision >= 1
            AND case_version_at_submit >= 0 AND request_version >= 0),
    CONSTRAINT chk_approval_request_active CHECK
        ((status = 'PENDING' AND active_case_id IS NOT NULL AND active_case_id = case_id)
            OR (status <> 'PENDING' AND active_case_id IS NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

ALTER TABLE cases
    ADD CONSTRAINT fk_cases_current_report FOREIGN KEY
        (tenant_id, event_id, id, current_report_version_id)
        REFERENCES report_versions (tenant_id, event_id, case_id, id);

CREATE TABLE approval_audit_events (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    case_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    approval_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    report_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    from_status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL,
    to_status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    report_content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    manifest_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_revision BIGINT NOT NULL,
    case_version_before BIGINT NOT NULL,
    case_version_after BIGINT NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_approval_audit_scope_time (tenant_id, event_id, case_id, occurred_at),
    CONSTRAINT fk_approval_audit_request FOREIGN KEY
        (tenant_id, event_id, case_id, approval_request_id)
        REFERENCES approval_requests (tenant_id, event_id, case_id, id),
    CONSTRAINT fk_approval_audit_report FOREIGN KEY
        (tenant_id, event_id, case_id, report_version_id)
        REFERENCES report_versions (tenant_id, event_id, case_id, id),
    CONSTRAINT fk_approval_audit_snapshot FOREIGN KEY
        (tenant_id, event_id, case_id, evidence_snapshot_id)
        REFERENCES evidence_snapshots (tenant_id, event_id, case_id, id),
    CONSTRAINT fk_approval_audit_actor FOREIGN KEY (tenant_id, actor_membership_id)
        REFERENCES memberships (tenant_id, id),
    CONSTRAINT chk_approval_audit_event CHECK (event_type = 'APPROVAL_REQUESTED'),
    CONSTRAINT chk_approval_audit_versions CHECK
        (evidence_revision >= 1 AND case_version_before >= 0
            AND case_version_after > case_version_before)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE issuance_holds (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    case_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_by_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_revision BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    released_at DATETIME(6) NULL,
    hold_version BIGINT NOT NULL,
    PRIMARY KEY (id),
    KEY idx_issuance_hold_scope_status (tenant_id, event_id, case_id, status),
    CONSTRAINT fk_issuance_hold_case FOREIGN KEY (tenant_id, event_id, case_id)
        REFERENCES cases (tenant_id, event_id, id),
    CONSTRAINT fk_issuance_hold_actor FOREIGN KEY (tenant_id, created_by_membership_id)
        REFERENCES memberships (tenant_id, id),
    CONSTRAINT chk_issuance_hold_status CHECK (status IN ('ACTIVE', 'RELEASED')),
    CONSTRAINT chk_issuance_hold_shape CHECK
        ((status = 'ACTIVE' AND released_at IS NULL)
            OR (status = 'RELEASED' AND released_at IS NOT NULL)),
    CONSTRAINT chk_issuance_hold_revision CHECK
        (evidence_revision >= 1 AND hold_version >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
