-- Apply once after admin-membership-event, Track 1 scope and fan-report-intake migrations.
-- This is additive. Existing rows and applied migration files are unchanged.

ALTER TABLE fan_reports
    ADD UNIQUE KEY uk_fan_reports_scope_id (tenant_id, event_id, id);
ALTER TABLE evidence_assets
    ADD UNIQUE KEY uk_evidence_assets_report_id (fan_report_id, id);
ALTER TABLE macro_audit_evidence
    ADD UNIQUE KEY uk_macro_evidence_scope_audit (tenant_id, internal_event_id, audit_id);

CREATE TABLE cases (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_by_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    assignee_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    related_case_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    relation_reason VARCHAR(2000) NULL,
    case_type ENUM('GRAPH_CLUSTER','FAN_REPORT','COMBINED') NOT NULL,
    status ENUM('OPEN','TRIAGED','IN_REVIEW','NEEDS_EVIDENCE','READY_FOR_APPROVAL',
        'APPROVED_FOR_REPORT','DISMISSED','CLOSED') NOT NULL,
    classification VARCHAR(160) NULL,
    case_version BIGINT NOT NULL,
    evidence_revision BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cases_scope_id (tenant_id, event_id, id),
    UNIQUE KEY uk_cases_tenant_id (tenant_id, id),
    KEY idx_cases_scope_status_created (tenant_id, event_id, status, created_at),
    KEY idx_cases_scope_creator (tenant_id, event_id, created_by_membership_id),
    KEY idx_cases_scope_assignee (tenant_id, event_id, assignee_membership_id),
    CONSTRAINT fk_cases_event FOREIGN KEY (tenant_id, event_id) REFERENCES events (tenant_id, id),
    CONSTRAINT fk_cases_creator FOREIGN KEY (tenant_id, created_by_membership_id)
        REFERENCES memberships (tenant_id, id),
    CONSTRAINT fk_cases_assignee FOREIGN KEY (tenant_id, assignee_membership_id)
        REFERENCES memberships (tenant_id, id),
    CONSTRAINT fk_cases_related FOREIGN KEY (tenant_id, event_id, related_case_id)
        REFERENCES cases (tenant_id, event_id, id),
    CONSTRAINT chk_cases_revision CHECK (evidence_revision >= 1 AND case_version >= 0),
    CONSTRAINT chk_cases_relation CHECK ((related_case_id IS NULL AND relation_reason IS NULL)
        OR (related_case_id IS NOT NULL AND relation_reason IS NOT NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE case_source_references (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    case_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_type ENUM('STORED_CLUSTER_EVIDENCE','FAN_REPORT','EVIDENCE_ASSET') NOT NULL,
    source_key_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    analysis_request_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    cluster_id VARCHAR(191) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    anchor_audit_id BIGINT NULL,
    analysis_algorithm VARCHAR(80) NULL,
    analysis_algorithm_version VARCHAR(80) NULL,
    analysis_completed_at DATETIME(6) NULL,
    risk_score DOUBLE NULL,
    stored_member_count INT NULL,
    fan_report_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    evidence_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    object_marker VARCHAR(160) NULL,
    linked_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_case_sources_case_key (case_id, source_key_digest),
    UNIQUE KEY uk_case_sources_case_id (case_id, id),
    KEY idx_case_sources_scope_key (tenant_id, event_id, source_key_digest),
    KEY idx_case_sources_report_asset (fan_report_id, evidence_id),
    KEY idx_case_sources_graph_anchor (tenant_id, event_id, anchor_audit_id),
    CONSTRAINT fk_case_sources_case FOREIGN KEY (tenant_id, event_id, case_id)
        REFERENCES cases (tenant_id, event_id, id),
    CONSTRAINT fk_case_sources_report FOREIGN KEY (tenant_id, event_id, fan_report_id)
        REFERENCES fan_reports (tenant_id, event_id, id),
    CONSTRAINT fk_case_sources_asset FOREIGN KEY (fan_report_id, evidence_id)
        REFERENCES evidence_assets (fan_report_id, id),
    CONSTRAINT fk_case_sources_graph FOREIGN KEY (tenant_id, event_id, anchor_audit_id)
        REFERENCES macro_audit_evidence (tenant_id, internal_event_id, audit_id),
    CONSTRAINT chk_case_source_shape CHECK (
        (source_type = 'STORED_CLUSTER_EVIDENCE' AND analysis_request_id IS NOT NULL
            AND cluster_id IS NOT NULL AND anchor_audit_id IS NOT NULL
            AND fan_report_id IS NULL AND evidence_id IS NULL)
        OR (source_type = 'FAN_REPORT' AND fan_report_id IS NOT NULL
            AND evidence_id IS NULL AND anchor_audit_id IS NULL
            AND analysis_request_id IS NULL AND cluster_id IS NULL)
        OR (source_type = 'EVIDENCE_ASSET' AND fan_report_id IS NOT NULL
            AND evidence_id IS NOT NULL AND anchor_audit_id IS NULL
            AND analysis_request_id IS NULL AND cluster_id IS NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE case_activities (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    case_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_role VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    from_status VARCHAR(32) NULL,
    to_status VARCHAR(32) NULL,
    version_before BIGINT NULL,
    version_after BIGINT NOT NULL,
    evidence_revision BIGINT NOT NULL,
    source_reference_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    previous_assignee_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    new_assignee_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_case_activities_scope_time (tenant_id, event_id, case_id, occurred_at),
    KEY idx_case_activities_actor (tenant_id, actor_membership_id),
    KEY idx_case_activities_source (case_id, source_reference_id),
    CONSTRAINT fk_case_activities_case FOREIGN KEY (tenant_id, event_id, case_id)
        REFERENCES cases (tenant_id, event_id, id),
    CONSTRAINT fk_case_activities_actor FOREIGN KEY (tenant_id, actor_membership_id)
        REFERENCES memberships (tenant_id, id),
    CONSTRAINT fk_case_activities_source FOREIGN KEY (case_id, source_reference_id)
        REFERENCES case_source_references (case_id, id),
    CONSTRAINT chk_case_activities_versions CHECK (version_after >= 0 AND evidence_revision >= 1),
    CONSTRAINT chk_case_activities_actor_role CHECK (actor_role IN ('ANALYST', 'REVIEWER'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE case_idempotency_records (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operation VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_case_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    key_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result_case_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    response_json TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_case_idempotency_scope_key
        (tenant_id, actor_membership_id, operation, target_case_id, key_digest),
    KEY idx_case_idempotency_result (tenant_id, result_case_id),
    CONSTRAINT fk_case_idempotency_actor FOREIGN KEY (tenant_id, actor_membership_id)
        REFERENCES memberships (tenant_id, id),
    CONSTRAINT fk_case_idempotency_result FOREIGN KEY (tenant_id, result_case_id)
        REFERENCES cases (tenant_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
