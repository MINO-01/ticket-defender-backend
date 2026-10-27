-- Apply once after case-w06-migration.sql. Existing W02-W06 rows and objects are unchanged.

ALTER TABLE evidence_verification_records
    ADD UNIQUE KEY uk_evidence_verifications_scope_id
        (tenant_id, event_id, case_id, evidence_snapshot_id, source_reference_id, evidence_id, id);

ALTER TABLE case_source_references
    ADD UNIQUE KEY uk_case_sources_case_id_evidence (case_id, id, evidence_id);

CREATE TABLE evidence_preservation_policies (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    policy_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    policy_version BIGINT NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    policy_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    effective_from DATETIME(6) NOT NULL,
    effective_until DATETIME(6) NOT NULL,
    retention_limit_days INT NOT NULL,
    storage_profile VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    preservation_mode VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    approval_authority VARCHAR(160) NULL,
    created_at DATETIME(6) NOT NULL,
    approved_at DATETIME(6) NULL,
    revoked_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_preservation_policy_version (policy_id, policy_version),
    UNIQUE KEY uk_preservation_policy_tenant_version (tenant_id, policy_id, policy_version),
    KEY idx_preservation_policy_scope (tenant_id, event_id, policy_id, policy_version),
    CONSTRAINT fk_preservation_policy_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT fk_preservation_policy_event FOREIGN KEY (tenant_id, event_id)
        REFERENCES events (tenant_id, id),
    CONSTRAINT chk_preservation_policy_version CHECK (policy_version >= 1),
    CONSTRAINT chk_preservation_policy_status CHECK (policy_status IN
        ('DRAFT', 'APPROVED', 'REVOKED', 'EXPIRED')),
    CONSTRAINT chk_preservation_policy_period CHECK (effective_until > effective_from),
    CONSTRAINT chk_preservation_policy_limit CHECK
        (retention_limit_days BETWEEN 1 AND 3650),
    CONSTRAINT chk_preservation_policy_mode CHECK (preservation_mode IN
        ('GOVERNANCE', 'COMPLIANCE')),
    CONSTRAINT chk_preservation_policy_approval CHECK (
        (policy_status = 'DRAFT' AND approval_authority IS NULL
            AND approved_at IS NULL AND revoked_at IS NULL)
        OR (policy_status IN ('APPROVED', 'EXPIRED') AND approval_authority IS NOT NULL
            AND approval_authority <> '' AND approved_at IS NOT NULL AND revoked_at IS NULL)
        OR (policy_status = 'REVOKED' AND approval_authority IS NOT NULL
            AND approval_authority <> '' AND approved_at IS NOT NULL AND revoked_at IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE evidence_preservation_records (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    case_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_reference_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    verification_record_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    preservation_policy_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    preservation_policy_version BIGINT NOT NULL,
    storage_profile VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_key_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actual_version_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_size BIGINT NOT NULL,
    object_lock_mode VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    retain_until DATETIME(6) NOT NULL,
    preservation_status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    verified_at DATETIME(6) NOT NULL,
    actor_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_preservation_object_policy (tenant_id, evidence_id, storage_profile,
        actual_version_id, preservation_policy_id, preservation_policy_version),
    KEY idx_preservation_case_time (tenant_id, event_id, case_id, verified_at),
    KEY idx_preservation_source_time (evidence_snapshot_id, source_reference_id, verified_at),
    CONSTRAINT fk_preservation_record_case FOREIGN KEY (tenant_id, event_id, case_id)
        REFERENCES cases (tenant_id, event_id, id),
    CONSTRAINT fk_preservation_record_snapshot FOREIGN KEY
        (tenant_id, event_id, case_id, evidence_snapshot_id)
        REFERENCES evidence_snapshots (tenant_id, event_id, case_id, id),
    CONSTRAINT fk_preservation_record_source FOREIGN KEY
        (case_id, source_reference_id, evidence_id)
        REFERENCES case_source_references (case_id, id, evidence_id),
    CONSTRAINT fk_preservation_record_asset FOREIGN KEY (evidence_id)
        REFERENCES evidence_assets (id),
    CONSTRAINT fk_preservation_record_verification FOREIGN KEY
        (tenant_id, event_id, case_id, evidence_snapshot_id, source_reference_id,
            evidence_id, verification_record_id)
        REFERENCES evidence_verification_records
            (tenant_id, event_id, case_id, evidence_snapshot_id, source_reference_id,
                evidence_id, id),
    CONSTRAINT fk_preservation_record_policy FOREIGN KEY
        (tenant_id, preservation_policy_id, preservation_policy_version)
        REFERENCES evidence_preservation_policies (tenant_id, policy_id, policy_version),
    CONSTRAINT fk_preservation_record_actor FOREIGN KEY (tenant_id, actor_membership_id)
        REFERENCES memberships (tenant_id, id),
    CONSTRAINT chk_preservation_record_status CHECK
        (preservation_status = 'IMMUTABLE_VERIFIED'),
    CONSTRAINT chk_preservation_record_mode CHECK
        (object_lock_mode IN ('GOVERNANCE', 'COMPLIANCE')),
    CONSTRAINT chk_preservation_record_size CHECK (object_size > 0),
    CONSTRAINT chk_preservation_record_retention CHECK (retain_until > verified_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE evidence_preservation_attempts (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    case_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_reference_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    verification_record_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    requested_policy_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    resolved_policy_version BIGINT NULL,
    preservation_record_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    actor_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    outcome VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    key_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempted_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_preservation_attempt_case_time (tenant_id, event_id, case_id, attempted_at),
    CONSTRAINT fk_preservation_attempt_case FOREIGN KEY (tenant_id, event_id, case_id)
        REFERENCES cases (tenant_id, event_id, id),
    CONSTRAINT fk_preservation_attempt_snapshot FOREIGN KEY
        (tenant_id, event_id, case_id, evidence_snapshot_id)
        REFERENCES evidence_snapshots (tenant_id, event_id, case_id, id),
    CONSTRAINT fk_preservation_attempt_source FOREIGN KEY
        (case_id, source_reference_id, evidence_id)
        REFERENCES case_source_references (case_id, id, evidence_id),
    CONSTRAINT fk_preservation_attempt_asset FOREIGN KEY (evidence_id)
        REFERENCES evidence_assets (id),
    CONSTRAINT fk_preservation_attempt_verification FOREIGN KEY
        (tenant_id, event_id, case_id, evidence_snapshot_id, source_reference_id,
            evidence_id, verification_record_id)
        REFERENCES evidence_verification_records
            (tenant_id, event_id, case_id, evidence_snapshot_id, source_reference_id,
                evidence_id, id),
    CONSTRAINT fk_preservation_attempt_record FOREIGN KEY (preservation_record_id)
        REFERENCES evidence_preservation_records (id),
    CONSTRAINT fk_preservation_attempt_actor FOREIGN KEY (tenant_id, actor_membership_id)
        REFERENCES memberships (tenant_id, id),
    CONSTRAINT chk_preservation_attempt_outcome CHECK (outcome IN
        ('VERIFIED', 'POLICY_REJECTED', 'STORAGE_REJECTED', 'STORAGE_UNAVAILABLE', 'CONFLICT')),
    CONSTRAINT chk_preservation_attempt_shape CHECK (
        (outcome = 'VERIFIED' AND preservation_record_id IS NOT NULL
            AND reason_code = 'IMMUTABLE_VERIFIED')
        OR (outcome <> 'VERIFIED' AND preservation_record_id IS NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
