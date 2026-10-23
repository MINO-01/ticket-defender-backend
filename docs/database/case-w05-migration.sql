-- Apply once after case-foundation-migration.sql. No existing rows are rewritten.
CREATE TABLE evidence_snapshots (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    case_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_revision BIGINT NOT NULL,
    case_version_at_capture BIGINT NOT NULL,
    manifest_schema_version INT NOT NULL,
    manifest_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    manifest_json TEXT NOT NULL,
    source_integrity VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_by_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    captured_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_evidence_snapshots_case_revision (case_id, evidence_revision),
    UNIQUE KEY uk_evidence_snapshots_scope_id (tenant_id, event_id, case_id, id),
    KEY idx_evidence_snapshots_scope_time (tenant_id, event_id, case_id, captured_at),
    CONSTRAINT fk_evidence_snapshots_case FOREIGN KEY (tenant_id, event_id, case_id)
        REFERENCES cases (tenant_id, event_id, id),
    CONSTRAINT fk_evidence_snapshots_actor FOREIGN KEY (tenant_id, created_by_membership_id)
        REFERENCES memberships (tenant_id, id),
    CONSTRAINT chk_evidence_snapshots_revision CHECK (evidence_revision >= 1 AND case_version_at_capture >= 0),
    CONSTRAINT chk_evidence_snapshots_schema CHECK (manifest_schema_version = 1),
    CONSTRAINT chk_evidence_snapshots_integrity CHECK (source_integrity IN ('NO_FILE', 'METADATA_ONLY'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE report_versions (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    case_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    evidence_revision BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    version BIGINT NOT NULL,
    template_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_json TEXT NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_by_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_report_versions_case_revision (case_id, revision),
    KEY idx_report_versions_scope_time (tenant_id, event_id, case_id, created_at),
    CONSTRAINT fk_report_versions_case FOREIGN KEY (tenant_id, event_id, case_id)
        REFERENCES cases (tenant_id, event_id, id),
    CONSTRAINT fk_report_versions_snapshot FOREIGN KEY (tenant_id, event_id, case_id, evidence_snapshot_id)
        REFERENCES evidence_snapshots (tenant_id, event_id, case_id, id),
    CONSTRAINT fk_report_versions_actor FOREIGN KEY (tenant_id, created_by_membership_id)
        REFERENCES memberships (tenant_id, id),
    CONSTRAINT chk_report_versions_revision CHECK (evidence_revision >= 1 AND revision >= 1 AND version >= 0),
    CONSTRAINT chk_report_versions_status CHECK (status = 'DRAFT')
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
