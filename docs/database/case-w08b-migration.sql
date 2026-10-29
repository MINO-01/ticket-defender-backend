-- Apply once after case-w08a-migration.sql. Existing W02-W08a rows remain unchanged.

CREATE TABLE review_receipts (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    case_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    approval_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    report_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    report_revision BIGINT NOT NULL,
    report_content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    report_template_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    report_template_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_revision BIGINT NOT NULL,
    manifest_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    approver_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    token_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    issued_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6) NULL,
    revoked_at DATETIME(6) NULL,
    active_approval_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    receipt_version BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_review_receipt_token (token_sha256),
    UNIQUE KEY uk_review_receipt_active
        (tenant_id, approver_membership_id, active_approval_request_id),
    UNIQUE KEY uk_review_receipt_scope_id (tenant_id, event_id, case_id, id),
    KEY idx_review_receipt_request_approver (approval_request_id, approver_membership_id, issued_at),
    CONSTRAINT fk_review_receipt_request FOREIGN KEY
        (tenant_id, event_id, case_id, approval_request_id)
        REFERENCES approval_requests (tenant_id, event_id, case_id, id),
    CONSTRAINT fk_review_receipt_report FOREIGN KEY
        (tenant_id, event_id, case_id, report_version_id)
        REFERENCES report_versions (tenant_id, event_id, case_id, id),
    CONSTRAINT fk_review_receipt_snapshot FOREIGN KEY
        (tenant_id, event_id, case_id, evidence_snapshot_id)
        REFERENCES evidence_snapshots (tenant_id, event_id, case_id, id),
    CONSTRAINT fk_review_receipt_approver FOREIGN KEY (tenant_id, approver_membership_id)
        REFERENCES memberships (tenant_id, id),
    CONSTRAINT chk_review_receipt_status CHECK (status IN
        ('ISSUED', 'CONSUMED', 'REVOKED', 'EXPIRED')),
    CONSTRAINT chk_review_receipt_shape CHECK (
        (status = 'ISSUED' AND active_approval_request_id IS NOT NULL
            AND active_approval_request_id = approval_request_id
            AND consumed_at IS NULL AND revoked_at IS NULL)
        OR (status = 'CONSUMED' AND active_approval_request_id IS NULL
            AND consumed_at IS NOT NULL AND revoked_at IS NULL)
        OR (status = 'REVOKED' AND active_approval_request_id IS NULL
            AND consumed_at IS NULL AND revoked_at IS NOT NULL)
        OR (status = 'EXPIRED' AND active_approval_request_id IS NULL
            AND consumed_at IS NULL AND revoked_at IS NULL)),
    CONSTRAINT chk_review_receipt_values CHECK
        (report_revision >= 1 AND evidence_revision >= 1 AND receipt_version >= 0
            AND expires_at > issued_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE approval_decisions (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    case_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
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
    outcome VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    decided_by_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    case_version_before BIGINT NOT NULL,
    case_version_after BIGINT NOT NULL,
    request_version_at_decision BIGINT NOT NULL,
    report_version_at_decision BIGINT NOT NULL,
    decided_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_approval_decision_request (approval_request_id),
    UNIQUE KEY uk_approval_decision_receipt (review_receipt_id),
    UNIQUE KEY uk_approval_decision_scope_id (tenant_id, event_id, case_id, id),
    KEY idx_approval_decision_scope_time (tenant_id, event_id, case_id, decided_at),
    CONSTRAINT fk_approval_decision_request FOREIGN KEY
        (tenant_id, event_id, case_id, approval_request_id)
        REFERENCES approval_requests (tenant_id, event_id, case_id, id),
    CONSTRAINT fk_approval_decision_receipt FOREIGN KEY
        (tenant_id, event_id, case_id, review_receipt_id)
        REFERENCES review_receipts (tenant_id, event_id, case_id, id),
    CONSTRAINT fk_approval_decision_report FOREIGN KEY
        (tenant_id, event_id, case_id, report_version_id)
        REFERENCES report_versions (tenant_id, event_id, case_id, id),
    CONSTRAINT fk_approval_decision_snapshot FOREIGN KEY
        (tenant_id, event_id, case_id, evidence_snapshot_id)
        REFERENCES evidence_snapshots (tenant_id, event_id, case_id, id),
    CONSTRAINT fk_approval_decision_actor FOREIGN KEY (tenant_id, decided_by_membership_id)
        REFERENCES memberships (tenant_id, id),
    CONSTRAINT chk_approval_decision_outcome CHECK (outcome IN ('APPROVE', 'REJECT')),
    CONSTRAINT chk_approval_decision_versions CHECK
        (report_revision >= 1 AND evidence_revision >= 1 AND case_version_before >= 0
            AND case_version_after > case_version_before
            AND request_version_at_decision >= 0 AND report_version_at_decision >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

ALTER TABLE approval_audit_events
    ADD COLUMN review_receipt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER approval_request_id,
    ADD COLUMN approval_decision_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER review_receipt_id,
    DROP CHECK chk_approval_audit_event,
    DROP CHECK chk_approval_audit_versions,
    ADD CONSTRAINT fk_approval_audit_receipt FOREIGN KEY
        (tenant_id, event_id, case_id, review_receipt_id)
        REFERENCES review_receipts (tenant_id, event_id, case_id, id),
    ADD CONSTRAINT fk_approval_audit_decision FOREIGN KEY
        (tenant_id, event_id, case_id, approval_decision_id)
        REFERENCES approval_decisions (tenant_id, event_id, case_id, id),
    ADD CONSTRAINT chk_approval_audit_event CHECK (event_type IN
        ('APPROVAL_REQUESTED', 'REVIEW_RECEIPT_ISSUED', 'APPROVAL_DECIDED',
         'REVIEW_RECEIPT_REVOKED', 'REVIEW_RECEIPT_EXPIRED', 'APPROVAL_WITHDRAWN',
         'APPROVAL_INVALIDATED')),
    ADD CONSTRAINT chk_approval_audit_versions CHECK
        (evidence_revision >= 1 AND case_version_before >= 0
            AND case_version_after >= case_version_before);
