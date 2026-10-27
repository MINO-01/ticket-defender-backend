-- Apply once after case-w05-migration.sql. Existing evidence and snapshot rows are unchanged.

CREATE TABLE evidence_verification_records (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    case_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    manifest_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_revision BIGINT NOT NULL,
    source_reference_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fan_report_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_key_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    observed_marker VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NULL,
    marker_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expected_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    verified_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    expected_byte_size BIGINT NOT NULL,
    verified_byte_size BIGINT NULL,
    media_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    retention_until DATETIME(6) NOT NULL,
    verification_status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    preservation_status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    verification_method VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_by_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    verified_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_evidence_verifications_case_time (tenant_id, event_id, case_id, verified_at),
    KEY idx_evidence_verifications_source_time (evidence_snapshot_id, source_reference_id, verified_at),
    CONSTRAINT fk_evidence_verifications_snapshot FOREIGN KEY (tenant_id, event_id, case_id, evidence_snapshot_id)
        REFERENCES evidence_snapshots (tenant_id, event_id, case_id, id),
    CONSTRAINT fk_evidence_verifications_source FOREIGN KEY (case_id, source_reference_id)
        REFERENCES case_source_references (case_id, id),
    CONSTRAINT fk_evidence_verifications_report FOREIGN KEY (tenant_id, event_id, fan_report_id)
        REFERENCES fan_reports (tenant_id, event_id, id),
    CONSTRAINT fk_evidence_verifications_asset FOREIGN KEY (fan_report_id, evidence_id)
        REFERENCES evidence_assets (fan_report_id, id),
    CONSTRAINT fk_evidence_verifications_actor FOREIGN KEY (tenant_id, created_by_membership_id)
        REFERENCES memberships (tenant_id, id),
    CONSTRAINT chk_evidence_verifications_status CHECK (verification_status IN
        ('MATCH', 'HASH_MISMATCH', 'SIZE_MISMATCH', 'OBJECT_MISSING', 'OBJECT_CHANGED',
         'STORAGE_UNAVAILABLE', 'TOO_LARGE', 'RETENTION_EXPIRED')),
    CONSTRAINT chk_evidence_verifications_preservation CHECK (preservation_status IN
        ('UNPROTECTED', 'VERSIONED_MUTABLE', 'UNKNOWN')),
    CONSTRAINT chk_evidence_verifications_marker CHECK (marker_kind IN ('VERSION_ID', 'ETAG', 'NONE')),
    CONSTRAINT chk_evidence_verifications_method CHECK (verification_method IN ('STREAMED_SHA256', 'NOT_READ')),
    CONSTRAINT chk_evidence_verifications_size CHECK (expected_byte_size > 0
        AND (verified_byte_size IS NULL OR verified_byte_size >= 0))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
