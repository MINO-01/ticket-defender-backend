-- Current-schema baseline for the current exp2 main schema only.
-- Product-code base: b5a7a2f9bb2f42c4a251cc42fbf852121825bbbb.
-- Cross-checked against 57 current JPA entities using Hibernate ORM 7.4.5.Final offline.
-- Reconciled against the final DDL contract represented by the existing 40 SQL files.
-- UUID columns follow the CHAR(36) ASCII contract in the existing SQL migrations.
-- Offline Hibernate export emitted varchar(36) for these UUID mappings; this baseline uses the migration contract.
-- A disposable MySQL ddl-auto:validate check remains required before any application.
-- No database connection was used for generation. This script has NOT been applied.
-- Precondition: run current-schema-preflight.sql, verify its output and the external backup SHA-256.
-- Target: MySQL 8.0.46 only; selected database must be the approved synthetic development database.
-- Not idempotent. Do not use mysql --force. Stop at the first error and do not rerun blindly.
-- MySQL DDL may commit statement by statement; after an error, stop and inspect with postflight queries.
-- Do not proceed while the MySQL host binding or network access restriction remains unresolved.
-- No source migration file is replayed here. Historical UPDATE/INSERT backfills and legacy macro copy are excluded.
-- Existing reservation and ticket_audit are ALTERed only. No DROP, TRUNCATE, DELETE, or table recreation occurs.
-- Keep ddl-auto=validate. PR 1 through PR 7 future schema is intentionally excluded.

-- Provenance mapping from all 40 existing migration files to their affected tables.
-- Historical files remain unchanged and are not executed by this baseline.
-- admin-evidence-content-audit-migration.sql: evidence_access_audit_events.
-- admin-membership-event-migration.sql: events, membership_events, membership_roles, memberships, tenants.
-- bulk-ledger-import-bl01-migration.sql: td_bulk_import_errors, td_bulk_import_jobs, td_bulk_import_scopes.
-- bulk-ledger-import-bl02-staging-migration.sql: td_bulk_import_jobs, td_bulk_import_staging_rows.
-- bulk-ledger-import-bl03-candidate-migration.sql: td_bulk_import_scopes, td_bulk_import_staging_rows, td_bulk_ledger_candidate_imports, td_bulk_ledger_change_events, td_bulk_ledger_publication_heads, td_bulk_ledger_ticket_versions, td_bulk_ledger_versions.
-- bulk-ledger-import-bl04-candidate-recovery-migration.sql: td_bulk_ledger_candidate_imports.
-- bulk-ledger-import-bl05-publication-migration.sql: td_bulk_import_jobs, td_bulk_import_scopes, td_bulk_ledger_candidate_imports, td_bulk_ledger_publication_history, td_bulk_ledger_publication_outbox, td_bulk_ledger_publications, td_bulk_ledger_versions.
-- bulk-ledger-import-bl06-track1-outbox-migration.sql: td_bulk_ledger_publication_outbox, td_bulk_ledger_track1_analysis_jobs, td_bulk_ledger_track1_ticket_signals, track1_purchase_limit_runs.
-- bulk-ledger-import-v3-raw-signal-migration.sql: td_bulk_import_staging_rows, td_bulk_import_upload_deletions.
-- bulk-ledger-score-v2-exp2-signal-migration.sql: td_bulk_ledger_track1_ticket_signals.
-- bulk-ledger-track1-auxiliary-results-migration.sql: td_bulk_ledger_track1_bulk_jobs, td_bulk_ledger_track1_bulk_limit_evaluations, td_bulk_ledger_track1_bulk_limit_finding_reservations, td_bulk_ledger_track1_bulk_limit_findings, td_bulk_ledger_track1_bulk_shared_signal_observations, td_bulk_ledger_track1_policy_snapshots.
-- bulk-ledger-track1-bulk-job-migration.sql: td_bulk_ledger_track1_bulk_clusters, td_bulk_ledger_track1_bulk_jobs, td_bulk_ledger_track1_bulk_members.
-- bulk-ledger-track1-signal-source-migration.sql: td_bulk_ledger_track1_ticket_signals.
-- case-foundation-migration.sql: case_activities, case_idempotency_records, case_source_references, cases, evidence_assets, fan_reports, macro_audit_evidence.
-- case-w05-migration.sql: evidence_snapshots, report_versions.
-- case-w06-migration.sql: evidence_verification_records.
-- case-w07-migration.sql: case_source_references, evidence_preservation_attempts, evidence_preservation_policies, evidence_preservation_records, evidence_verification_records.
-- case-w08a-migration.sql: approval_audit_events, approval_requests, cases, issuance_holds, report_versions.
-- case-w08b-migration.sql: approval_audit_events, approval_decisions, case_activities, review_receipts.
-- case-w09-official-pdf-migration.sql: official_pdf_artifacts, official_pdf_audit_events, official_pdf_issuance_attempts, official_pdf_issuances.
-- case-w10-track2-candidate-source-migration.sql: case_source_references, evidence_snapshots.
-- fan-report-intake-migration.sql: events, evidence_assets, fan_reports, public_request_quota.
-- multi-event-artifact-job-migration.sql: td_multi_event_investigation_runs.
-- multi-event-investigation-migration.sql: td_multi_event_investigation_candidates, td_multi_event_investigation_members, td_multi_event_investigation_relationship_members, td_multi_event_investigation_relationship_sources, td_multi_event_investigation_relationships, td_multi_event_investigation_runs, td_multi_event_investigation_scopes.
-- multi-event-multi-performance-migration.sql: td_multi_event_investigation_candidates, td_multi_event_investigation_runs.
-- multi-event-quantity-snapshot-migration.sql: td_multi_event_investigation_scopes.
-- multi-event-source-binding-migration.sql: td_bulk_ledger_track1_policy_snapshots, td_bulk_ledger_versions, td_multi_event_investigation_scopes.
-- official-pdf-synthetic-isolation-migration.sql: official_pdf_synthetic_tenants.
-- purchase-limit-policy-lifecycle-migration.sql: purchase_limit_policies.
-- purchase-limit-policy-migration.sql: purchase_limit_policies, purchase_limit_policy_audits.
-- purchase-limit-policy-scope-migration.sql: purchase_limit_policies, purchase_limit_policy_audits.
-- purchase-limit-policy-semantics-migration.sql: purchase_limit_policies, purchase_limit_policy_audits.
-- track1-capstone-demo-v1-migration.sql: track1_purchase_limit_shared_signal_observations.
-- track1-macro-audit-index-migration.sql: macro_audit_evidence, ticket_audit.
-- track1-purchase-ledger-migration.sql: track1_purchase_ledger_batches, track1_purchase_ledger_errors, track1_purchase_ledger_rows, track1_purchase_limit_finding_reservations, track1_purchase_limit_findings, track1_purchase_limit_run_clusters, track1_purchase_limit_runs.
-- track1-tenant-scoped-evidence-migration.sql: macro_audit_evidence, service_tenant_bindings.
-- track2-candidate-multiseat-migration.sql: reservation_candidates.
-- track2-candidate-pipeline-migration.sql: fan_report_performance_links, listing_candidate_match_runs, listing_context_analyses, performance_link_audits, performance_verification_audits, reservation_candidates, reservation_data_sources, reservation_import_batches, reservation_import_errors, synthetic_reservation_import_tenants, verified_performances.
-- track2-listing-analysis-migration.sql: listing_context_analyses, listing_context_observations.
-- track2-reservation-scope-migration.sql: reservation, ticket_audit.
-- Conditional DDL was consolidated: track1_purchase_ledger_rows.track2_reservation_ref_hmac,
-- track1_purchase_ledger_batches.track2_link_digest, macro_audit_evidence.reference_purpose,
-- and chk_t1_ledger_track2_reference. Historical backfills are omitted.

-- Existing reservation: retain legacy columns, row values, primary/unique keys and idx_seat_location.
ALTER TABLE reservation
    ADD COLUMN tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN performance_ref VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    MODIFY COLUMN zone VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    MODIFY COLUMN row_num VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    MODIFY COLUMN seat_num VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    ADD KEY idx_reservation_scope (tenant_id, event_id, performance_ref);

-- Existing ticket_audit: preserve rows, conditionally remove the two legacy indexes,
-- then add current Entity fields/indexes. Review their exact definitions in preflight before applying.
SET @td_sql = IF(
    EXISTS (SELECT 1 FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'ticket_audit'
              AND index_name = 'uk_payment_account'),
    'ALTER TABLE ticket_audit DROP INDEX uk_payment_account',
    'SELECT ''ticket_audit.uk_payment_account absent; skipped'' AS baseline_note'
);
PREPARE td_drop_legacy_index FROM @td_sql;
EXECUTE td_drop_legacy_index;
DEALLOCATE PREPARE td_drop_legacy_index;
SET @td_sql = IF(
    EXISTS (SELECT 1 FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'ticket_audit'
              AND index_name = 'idx_ticket_audit_event_reservation_evidence'),
    'ALTER TABLE ticket_audit DROP INDEX idx_ticket_audit_event_reservation_evidence',
    'SELECT ''ticket_audit.idx_ticket_audit_event_reservation_evidence absent; skipped'' AS baseline_note'
);
PREPARE td_drop_legacy_index FROM @td_sql;
EXECUTE td_drop_legacy_index;
DEALLOCATE PREPARE td_drop_legacy_index;

-- Preserve the current Entity index when its exact definition already exists.
-- The read-only preflight stops if an existing same-name index has a different shape.
SET @td_sql = IF(
    EXISTS (SELECT 1 FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'ticket_audit'
              AND index_name = 'uk_reservation_reporter'),
    'SELECT ''ticket_audit.uk_reservation_reporter already present; preserved'' AS baseline_note',
    'ALTER TABLE ticket_audit ADD UNIQUE KEY uk_reservation_reporter (reservation_no, reporter_id)'
);
PREPARE td_ensure_reservation_reporter FROM @td_sql;
EXECUTE td_ensure_reservation_reporter;
DEALLOCATE PREPARE td_ensure_reservation_reporter;

ALTER TABLE ticket_audit
    ADD COLUMN report_received_at DATETIME(6) NULL,
    ADD COLUMN evidence_image_urls TEXT NULL,
    MODIFY COLUMN status ENUM('DUPLICATED','FRAUD_DETECTED','NORMAL','REPORT_ISSUED','MATCH_CANDIDATE') NULL,
    ADD KEY idx_ticket_audit_reservation_evidence_status (reservation_no, evidence_type, status);

CREATE TABLE approval_audit_events (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    actor_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    approval_decision_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    approval_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    case_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    case_version_after bigint not null,
    case_version_before bigint not null,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    event_type varchar(48) not null,
    evidence_revision bigint not null,
    evidence_snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    from_status varchar(24),
    manifest_sha256 varchar(64) not null,
    occurred_at datetime(6) not null,
    reason varchar(2000) not null,
    report_content_sha256 varchar(64) not null,
    report_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    review_receipt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    to_status varchar(24) not null,
    primary key (id),
    KEY idx_approval_audit_scope_time (tenant_id, event_id, case_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE approval_decisions (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    approval_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    case_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    case_version_after bigint not null,
    case_version_before bigint not null,
    decided_at datetime(6) not null,
    decided_by_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    evidence_revision bigint not null,
    evidence_snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    manifest_sha256 varchar(64) not null,
    outcome enum ('APPROVE','REJECT') not null,
    reason varchar(2000) not null,
    report_content_sha256 varchar(64) not null,
    report_revision bigint not null,
    report_template_sha256 varchar(64) not null,
    report_template_version varchar(64) not null,
    report_version_at_decision bigint not null,
    report_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    request_version_at_decision bigint not null,
    review_receipt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    primary key (id),
    KEY idx_approval_decision_scope_time (tenant_id, event_id, case_id, decided_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE approval_requests (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    active_case_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    case_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    case_version_at_submit bigint not null,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    evidence_revision bigint not null,
    evidence_snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    manifest_sha256 varchar(64) not null,
    reason varchar(2000) not null,
    report_content_sha256 varchar(64) not null,
    report_revision bigint not null,
    report_template_sha256 varchar(64) not null,
    report_template_version varchar(64) not null,
    report_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    requested_at datetime(6) not null,
    requested_by_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    status enum ('APPROVED','INVALIDATED','PENDING','REJECTED','WITHDRAWN') not null,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    request_version bigint not null,
    primary key (id),
    KEY idx_approval_request_scope_time (tenant_id, event_id, case_id, requested_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE case_activities (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    actor_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    actor_role varchar(24) not null,
    case_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    event_type varchar(48) not null,
    evidence_revision bigint not null,
    from_status varchar(32),
    new_assignee_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    occurred_at datetime(6) not null,
    previous_assignee_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    reason varchar(2000) not null,
    source_reference_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    to_status varchar(32),
    version_after bigint not null,
    version_before bigint,
    primary key (id),
    KEY idx_case_activities_scope_time (tenant_id, event_id, case_id, occurred_at),
    KEY idx_case_activities_actor (tenant_id, actor_membership_id),
    KEY idx_case_activities_source (case_id, source_reference_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE case_idempotency_records (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    actor_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    created_at datetime(6) not null,
    key_digest varchar(64) not null,
    operation varchar(32) not null,
    request_digest varchar(64) not null,
    response_json text not null,
    result_case_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    target_case_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    primary key (id),
    KEY idx_case_idempotency_result (tenant_id, result_case_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE case_source_references (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    analysis_algorithm varchar(80),
    analysis_algorithm_version varchar(80),
    analysis_completed_at datetime(6),
    analysis_request_id varchar(64),
    anchor_audit_id bigint,
    candidate_analysis_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    candidate_analysis_response_sha256 varchar(64),
    candidate_analysis_row_version bigint,
    candidate_count integer,
    candidate_evidence_sha256 varchar(64),
    candidate_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    candidate_performance_link_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    candidate_performance_link_revision integer,
    candidate_reason varchar(64),
    candidate_reservation_batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    candidate_reservation_source_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    candidate_reservation_source_revision integer,
    candidate_reservation_source_version varchar(128),
    candidate_status varchar(32),
    candidate_verified_performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    candidate_verified_performance_revision integer,
    case_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    cluster_id varchar(191),
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    evidence_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    fan_report_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    linked_at datetime(6) not null,
    listing_candidate_match_run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    object_marker varchar(160),
    risk_score float(53),
    source_key_digest varchar(64) not null,
    source_type enum ('EVIDENCE_ASSET','FAN_REPORT','LISTING_CANDIDATE_MATCH','STORED_CLUSTER_EVIDENCE') not null,
    stored_member_count integer,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    primary key (id),
    UNIQUE KEY uk_case_sources_case_id (case_id, id),
    KEY idx_case_sources_scope_key (tenant_id, event_id, source_key_digest),
    KEY idx_case_sources_report_asset (fan_report_id, evidence_id),
    KEY idx_case_sources_graph_anchor (tenant_id, event_id, anchor_audit_id),
    UNIQUE KEY uk_case_sources_case_id_evidence (case_id, id, evidence_id),
    KEY idx_case_sources_candidate_run (tenant_id, event_id, listing_candidate_match_run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cases (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    assignee_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    case_version bigint not null,
    classification varchar(160),
    created_at datetime(6) not null,
    created_by_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    current_report_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    evidence_revision bigint not null,
    related_case_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    relation_reason varchar(2000),
    status enum ('APPROVED_FOR_REPORT','CLOSED','DISMISSED','IN_REVIEW','NEEDS_EVIDENCE','OPEN','READY_FOR_APPROVAL','TRIAGED') not null,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    case_type enum ('COMBINED','FAN_REPORT','GRAPH_CLUSTER') not null,
    updated_at datetime(6) not null,
    primary key (id),
    UNIQUE KEY uk_cases_tenant_id (tenant_id, id),
    KEY idx_cases_scope_status_created (tenant_id, event_id, status, created_at),
    KEY idx_cases_scope_creator (tenant_id, event_id, created_by_membership_id),
    KEY idx_cases_scope_assignee (tenant_id, event_id, assignee_membership_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE events (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    external_event_ref varchar(128) not null,
    name varchar(255) not null,
    public_intake_enabled bit not null,
    status varchar(24) not null,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    primary key (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE evidence_access_audit_events (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    actor_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    detail_code varchar(64) not null,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    evidence_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    fan_report_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    occurred_at datetime(6) not null,
    outcome enum ('FAILED','SUCCEEDED') not null,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    primary key (id),
    KEY idx_evidence_access_audit_scope_time (tenant_id, event_id, fan_report_id, occurred_at),
    KEY idx_evidence_access_audit_evidence_time (evidence_id, occurred_at),
    KEY idx_evidence_access_audit_report_evidence (fan_report_id, evidence_id),
    KEY idx_evidence_access_audit_actor (tenant_id, actor_membership_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE evidence_assets (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    byte_size bigint not null,
    created_at datetime(6) not null,
    fan_report_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    media_type varchar(32) not null,
    object_key varchar(256) not null,
    object_version varchar(160),
    retention_until datetime(6) not null,
    sha256 varchar(64) not null,
    storage_status enum ('FAILED','PENDING','STORED') not null,
    primary key (id),
    KEY idx_evidence_assets_report (fan_report_id),
    UNIQUE KEY uk_evidence_assets_report_id (fan_report_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE evidence_preservation_attempts (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    actor_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    attempted_at datetime(6) not null,
    case_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    evidence_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    evidence_snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    key_digest varchar(64) not null,
    outcome enum ('CONFLICT','POLICY_REJECTED','STORAGE_REJECTED','STORAGE_UNAVAILABLE','VERIFIED') not null,
    preservation_record_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    reason_code varchar(64) not null,
    request_digest varchar(64) not null,
    requested_policy_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    resolved_policy_version bigint,
    source_reference_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    verification_record_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    primary key (id),
    KEY idx_preservation_attempt_case_time (tenant_id, event_id, case_id, attempted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE evidence_preservation_policies (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    approval_authority varchar(160),
    approved_at datetime(6),
    created_at datetime(6) not null,
    effective_from datetime(6) not null,
    effective_until datetime(6) not null,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    policy_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    policy_status enum ('APPROVED','DRAFT','EXPIRED','REVOKED') not null,
    policy_version bigint not null,
    preservation_mode enum ('COMPLIANCE','GOVERNANCE') not null,
    retention_limit_days integer not null,
    revoked_at datetime(6),
    storage_profile varchar(64) not null,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    primary key (id),
    UNIQUE KEY uk_preservation_policy_tenant_version (tenant_id, policy_id, policy_version),
    KEY idx_preservation_policy_scope (tenant_id, event_id, policy_id, policy_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE evidence_preservation_records (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    actor_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    actual_version_id varchar(160) not null,
    case_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    evidence_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    evidence_snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    verification_record_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    object_key_sha256 varchar(64) not null,
    object_lock_mode enum ('COMPLIANCE','GOVERNANCE') not null,
    object_sha256 varchar(64) not null,
    object_size bigint not null,
    preservation_policy_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    preservation_policy_version bigint not null,
    preservation_status enum ('IMMUTABLE_VERIFIED') not null,
    retain_until datetime(6) not null,
    source_reference_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    storage_profile varchar(64) not null,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    verified_at datetime(6) not null,
    primary key (id),
    KEY idx_preservation_case_time (tenant_id, event_id, case_id, verified_at),
    KEY idx_preservation_source_time (evidence_snapshot_id, source_reference_id, verified_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE evidence_snapshots (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    captured_at datetime(6) not null,
    case_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    case_version_at_capture bigint not null,
    created_by_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    evidence_revision bigint not null,
    manifest_json text not null,
    manifest_schema_version integer not null,
    manifest_sha256 varchar(64) not null,
    source_integrity varchar(32) not null,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    primary key (id),
    KEY idx_evidence_snapshots_scope_time (tenant_id, event_id, case_id, captured_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE evidence_verification_records (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    case_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    created_by_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    evidence_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    evidence_revision bigint not null,
    evidence_snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    expected_byte_size bigint not null,
    expected_sha256 varchar(64) not null,
    fan_report_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    manifest_sha256 varchar(64) not null,
    marker_kind enum ('ETAG','NONE','VERSION_ID') not null,
    media_type varchar(32) not null,
    object_key_sha256 varchar(64) not null,
    observed_marker varchar(160),
    preservation_status enum ('UNKNOWN','UNPROTECTED','VERSIONED_MUTABLE') not null,
    retention_until datetime(6) not null,
    source_reference_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    verification_method enum ('NOT_READ','STREAMED_SHA256') not null,
    verification_status enum ('HASH_MISMATCH','MATCH','OBJECT_CHANGED','OBJECT_MISSING','RETENTION_EXPIRED','SIZE_MISMATCH','STORAGE_UNAVAILABLE','TOO_LARGE') not null,
    verified_at datetime(6) not null,
    verified_byte_size bigint,
    verified_sha256 varchar(64),
    primary key (id),
    KEY idx_evidence_verifications_case_time (tenant_id, event_id, case_id, verified_at),
    KEY idx_evidence_verifications_source_time (evidence_snapshot_id, source_reference_id, verified_at),
    UNIQUE KEY uk_evidence_verifications_scope_id
        (tenant_id, event_id, case_id, evidence_snapshot_id, source_reference_id, evidence_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE fan_report_performance_links (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    active_fan_report_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    created_at datetime(6) not null,
    ended_at datetime(6),
    ended_by_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    fan_report_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    link_revision integer not null,
    linked_by_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    performance_ref varchar(128) not null,
    performance_revision integer not null,
    reason varchar(512) not null,
    row_version bigint not null,
    status enum ('ACTIVE','REPLACED','WITHDRAWN') not null,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    primary key (id),
    KEY idx_fan_report_performance_links_report (fan_report_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE fan_reports (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    asking_price decimal(14,2),
    description text not null,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    idempotency_key_digest varchar(64) not null,
    intake_status enum ('ANALYSIS_FAILED','ANALYSIS_PENDING','PENDING_STORAGE','RECEIVED','STORAGE_FAILED','UNVERIFIABLE') not null,
    listing_url varchar(2048),
    lookup_secret_digest varchar(64) not null,
    platform varchar(160),
    receipt_number varchar(40) not null,
    received_at datetime(6) not null,
    report_type enum ('AUTOMATION_OFFER','FAKE_TICKET','OTHER','PREMIUM_RESALE') not null,
    request_digest varchar(64) not null,
    retention_until datetime(6) not null,
    seat_area varchar(160),
    seller varchar(160),
    submitted_performance_at datetime(6) not null,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    verified_performance_ref varchar(128),
    primary key (id),
    KEY idx_fan_reports_scope_received (tenant_id, event_id, received_at),
    UNIQUE KEY uk_fan_reports_scope_id (tenant_id, event_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE issuance_holds (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    case_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    created_at datetime(6) not null,
    created_by_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    evidence_revision bigint not null,
    reason_code varchar(64) not null,
    released_at datetime(6),
    status enum ('ACTIVE','RELEASED') not null,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    hold_version bigint not null,
    primary key (id),
    KEY idx_issuance_hold_scope_status (tenant_id, event_id, case_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE listing_candidate_match_runs (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    active_analysis_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    analysis_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    analysis_response_sha256 varchar(64),
    analysis_row_version bigint,
    candidate_count integer,
    candidate_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    completed_at datetime(6),
    created_at datetime(6) not null,
    created_by_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    evidence_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    evidence_sha256 varchar(64),
    fan_report_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    idempotency_key_digest varchar(64) not null,
    lease_expires_at datetime(6),
    performance_link_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    performance_link_revision integer,
    reason enum ('ANALYSIS_NOT_SUCCEEDED','ANALYSIS_OR_EVIDENCE_CHANGED','LOOKUP_STARTED','MATCHED_SINGLE_CANDIDATE','MULTIPLE_CANDIDATES','NO_CANDIDATE_FOUND','PERFORMANCE_LINK_UNVERIFIED','PERFORMANCE_REVISION_CHANGED','PERFORMANCE_WITHDRAWN','RESERVATION_LOOKUP_FAILED','RESERVATION_SOURCE_INCOMPLETE','RESERVATION_SOURCE_UNAVAILABLE','SEAT_OBSERVATION_AMBIGUOUS','SEAT_OBSERVATION_UNAVAILABLE') not null,
    request_digest varchar(64) not null,
    reservation_batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    reservation_source_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    reservation_source_revision integer,
    reservation_source_version varchar(128),
    row_version bigint not null,
    status enum ('AMBIGUOUS','ANALYSIS_FAILED','IN_PROGRESS','LOOKUP_FAILED','MATCH_CANDIDATE','NO_MATCH','UNVERIFIABLE') not null,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    verified_performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    verified_performance_revision integer,
    primary key (id),
    KEY idx_listing_candidate_runs_analysis_time (analysis_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE listing_context_analyses (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    active_evidence_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    analysis_status enum ('COMPLETED','FAILED','PARTIAL','UNVERIFIABLE'),
    analyzed_at datetime(6),
    analyzer_error_code varchar(64),
    analyzer_http_status integer,
    candidate_match_reason varchar(64),
    candidate_match_status varchar(32),
    completed_at datetime(6),
    created_at datetime(6) not null,
    created_by_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    error_code varchar(64),
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    evidence_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    execution_status enum ('ABANDONED','ANALYZER_FAILED','INPUT_REJECTED','IN_PROGRESS','STORAGE_FAILED','SUCCEEDED') not null,
    expected_byte_size bigint not null,
    expected_sha256 varchar(64) not null,
    fan_report_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    idempotency_key_digest varchar(64) not null,
    lease_expires_at datetime(6) not null,
    media_type varchar(32) not null,
    model_name varchar(128),
    model_version varchar(128),
    object_key_sha256 varchar(64) not null,
    object_marker varchar(160),
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    response_sha256 varchar(64),
    review_required bit,
    row_version bigint not null,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    verified_byte_size bigint,
    verified_sha256 varchar(64),
    primary key (id),
    KEY idx_listing_analyses_evidence_time (evidence_id, created_at),
    KEY idx_listing_analyses_scope (tenant_id, event_id, fan_report_id),
    UNIQUE KEY uk_listing_analyses_scope_id (tenant_id, event_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE listing_context_observations (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    analysis_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    candidate_1 varchar(128),
    candidate_2 varchar(128),
    candidate_3 varchar(128),
    evidence_quote varchar(512),
    field_name enum ('askingPrice','currency','eventName','performanceAt','platformDisplayName','row','saleText','seat','zone') not null,
    price_amount varchar(15),
    price_raw varchar(64),
    state enum ('AMBIGUOUS','FOUND','UNKNOWN') not null,
    unknown_reason enum ('ABSENT','NOT_CONFIRMED','UNREADABLE'),
    value_text varchar(256),
    primary key (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE macro_audit_evidence (
    audit_id bigint not null,
    account_id varchar(255) not null,
    analysis_algorithm varchar(255) not null,
    analysis_algorithm_version varchar(255) not null,
    analysis_completed_at datetime(6) not null,
    analysis_request_id varchar(255) not null,
    cluster_id varchar(255) not null,
    device_id_hash varchar(255),
    event_id varchar(255) not null,
    evidence_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin not null,
    internal_event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    ip_hash varchar(255),
    reference_purpose varchar(64) default 'LEGACY_V1_UNLINKABLE' not null,
    reservation_no varchar(255) not null,
    risk_score double not null,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    primary key (audit_id),
    KEY idx_macro_audit_evidence_scope (tenant_id, internal_event_id),
    UNIQUE KEY uk_macro_evidence_scope_audit (tenant_id, internal_event_id, audit_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE membership_events (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    primary key (id),
    KEY idx_membership_events_tenant_member (tenant_id, membership_id),
    KEY idx_membership_events_tenant_event (tenant_id, event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE membership_roles (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    role varchar(24) not null,
    primary key (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE memberships (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    status varchar(24) not null,
    subject varchar(191) not null,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    primary key (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE official_pdf_artifacts (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    approval_decision_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    approval_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    case_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    evidence_revision bigint not null,
    evidence_snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    generated_at datetime(6) not null,
    issuance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    manifest_sha256 varchar(64) not null,
    object_key varchar(512) not null,
    object_version varchar(512) not null,
    official_template_sha256 varchar(64) not null,
    official_template_version varchar(64) not null,
    pdf_sha256 varchar(64) not null,
    pdf_size bigint not null,
    report_content_sha256 varchar(64) not null,
    report_revision bigint not null,
    report_template_sha256 varchar(64) not null,
    report_template_version varchar(64) not null,
    report_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    review_receipt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    primary key (id),
    KEY idx_official_pdf_artifact_scope_time (tenant_id, event_id, case_id, generated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE official_pdf_audit_events (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    actor_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    artifact_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    case_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    detail_code varchar(96) not null,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    event_type varchar(48) not null,
    issuance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    occurred_at datetime(6) not null,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    primary key (id),
    KEY idx_official_pdf_audit_scope_time (tenant_id, event_id, case_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE official_pdf_issuance_attempts (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    actor_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    attempt_number integer not null,
    failure_code varchar(96),
    finished_at datetime(6),
    issuance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    attempt_kind enum ('RECOVERY','REQUEST','RETRY') not null,
    started_at datetime(6) not null,
    status enum ('FAILED','RUNNING','STALE','SUCCEEDED') not null,
    primary key (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE official_pdf_issuances (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    approval_decision_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    approval_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    attempt_count integer not null,
    candidate_object_key varchar(512) not null,
    candidate_object_version varchar(512),
    candidate_pdf_sha256 varchar(64),
    candidate_pdf_size bigint,
    case_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    completed_at datetime(6),
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    evidence_revision bigint not null,
    evidence_snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    last_failure_code varchar(96),
    manifest_sha256 varchar(64) not null,
    official_template_sha256 varchar(64) not null,
    official_template_version varchar(64) not null,
    report_content_sha256 varchar(64) not null,
    report_revision bigint not null,
    report_template_sha256 varchar(64) not null,
    report_template_version varchar(64) not null,
    report_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    requested_at datetime(6) not null,
    requested_by_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    review_receipt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    status enum ('FAILED','GENERATED','OBJECT_STORED','RENDERING','RESERVED','STALE') not null,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    issuance_version bigint not null,
    primary key (id),
    KEY idx_official_pdf_issuance_scope_time (tenant_id, event_id, case_id, requested_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE official_pdf_synthetic_tenants (
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    isolation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    registered_at datetime(6) not null,
    primary key (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE performance_link_audits (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    action enum ('LINKED','REPLACED','WITHDRAWN') not null,
    actor_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    fan_report_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    idempotency_key_digest varchar(64) not null,
    link_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    link_revision integer not null,
    occurred_at datetime(6) not null,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    reason varchar(512) not null,
    request_digest varchar(64) not null,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    primary key (id),
    KEY idx_performance_link_audit_link (link_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE performance_verification_audits (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    action enum ('REVISED','VERIFIED','WITHDRAWN') not null,
    actor_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    occurred_at datetime(6) not null,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    performance_ref varchar(128) not null,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    revision integer not null,
    source_version varchar(128) not null,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    verification_basis varchar(512) not null,
    primary key (id),
    KEY idx_performance_verification_audit_scope (tenant_id, event_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE purchase_limit_policies (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    active_performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    active_scope_key varchar(256),
    application_scope enum ('EVENT','PERFORMANCE','SALES_PHASE'),
    cancellation_refund_rule enum ('GROSS','NETTED'),
    changed_at datetime(6) not null,
    changed_by_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    effective_from datetime(6) not null,
    effective_until datetime(6),
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    includes_presale bit,
    limit_subject enum ('ACCOUNT','PERSON'),
    max_tickets_per_account integer not null,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    policy_scope_key varchar(256) not null,
    policy_source varchar(512) not null,
    policy_version varchar(128) not null,
    revision integer not null,
    row_version bigint not null,
    sales_phase_ref varchar(128),
    status enum ('ACTIVE','WITHDRAWN') not null,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    verification_basis varchar(512) not null,
    primary key (id),
    KEY idx_purchase_limit_policy_scope_status (tenant_id,event_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE purchase_limit_policy_audits (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    action enum ('CREATED','REVISED','WITHDRAWN') not null,
    actor_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    application_scope enum ('EVENT','PERFORMANCE','SALES_PHASE'),
    cancellation_refund_rule enum ('GROSS','NETTED'),
    effective_from datetime(6) not null,
    effective_until datetime(6),
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    includes_presale bit,
    limit_subject enum ('ACCOUNT','PERSON'),
    max_tickets_per_account integer not null,
    occurred_at datetime(6) not null,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    policy_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    policy_scope_key varchar(256),
    policy_source varchar(512) not null,
    policy_version varchar(128) not null,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    revision integer not null,
    sales_phase_ref varchar(128),
    status enum ('ACTIVE','WITHDRAWN') not null,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    verification_basis varchar(512) not null,
    primary key (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE report_versions (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    case_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    content_json text not null,
    content_sha256 varchar(64) not null,
    created_at datetime(6) not null,
    created_by_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    evidence_revision bigint not null,
    evidence_snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    revision bigint not null,
    status varchar(24) not null,
    template_sha256 varchar(64),
    template_version varchar(64) not null,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    version bigint not null,
    primary key (id),
    KEY idx_report_versions_scope_time (tenant_id, event_id, case_id, created_at),
    UNIQUE KEY uk_report_versions_scope_id (tenant_id, event_id, case_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE reservation_candidates (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    reference_hmac_key_version varchar(32) not null,
    reservation_ref_hmac varchar(64) not null,
    row_num varchar(128) not null,
    seat_num varchar(128) not null,
    source_row_index integer not null,
    ticket_ref_hmac varchar(64),
    zone varchar(128) not null,
    primary key (id),
    KEY idx_reservation_candidate_seat (batch_id, zone, row_num, seat_num)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE reservation_data_sources (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    complete_snapshot bit not null,
    current_batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    current_source_version varchar(128) not null,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    row_version bigint not null,
    source_revision integer not null,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    updated_at datetime(6) not null,
    primary key (id),
    KEY idx_reservation_data_source_current (current_batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE reservation_import_batches (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    accepted_row_count integer not null,
    complete_snapshot bit not null,
    completed_at datetime(6) not null,
    created_at datetime(6) not null,
    created_by_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    idempotency_key_digest varchar(64) not null,
    input_row_count integer not null,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    performance_ref varchar(128) not null,
    reference_hmac_key_version varchar(32) not null,
    rejected_row_count integer not null,
    request_digest varchar(64) not null,
    row_version bigint not null,
    source_version varchar(128) not null,
    status enum ('ACCEPTED','REJECTED') not null,
    synthetic_isolation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    primary key (id),
    KEY idx_reservation_import_batches_scope_time (tenant_id, event_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE reservation_import_errors (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    code varchar(64) not null,
    row_index integer not null,
    primary key (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE review_receipts (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    active_approval_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    approval_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    approver_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    case_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    consumed_at datetime(6),
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    evidence_revision bigint not null,
    evidence_snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    expires_at datetime(6) not null,
    issued_at datetime(6) not null,
    manifest_sha256 varchar(64) not null,
    report_content_sha256 varchar(64) not null,
    report_revision bigint not null,
    report_template_sha256 varchar(64) not null,
    report_template_version varchar(64) not null,
    report_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    revoked_at datetime(6),
    status enum ('CONSUMED','EXPIRED','ISSUED','REVOKED') not null,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    token_sha256 varchar(64) not null,
    receipt_version bigint not null,
    primary key (id),
    KEY idx_review_receipt_request_approver (approval_request_id, approver_membership_id, issued_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE service_tenant_bindings (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    client_id varchar(191) not null,
    issuer varchar(255) not null,
    status varchar(24) not null,
    subject varchar(191) not null,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    primary key (id),
    KEY idx_service_tenant_bindings_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE synthetic_reservation_import_tenants (
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    isolation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    registered_at datetime(6) not null,
    primary key (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE td_bulk_import_errors (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    code varchar(64) not null,
    created_at datetime(6) not null,
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    sequence_no bigint not null,
    primary key (id),
    UNIQUE KEY uk_td_bulk_import_error_sequence (job_id, sequence_no),
    KEY idx_td_bulk_import_error_job (job_id, sequence_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE td_bulk_import_jobs (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    accepted_row_count bigint not null,
    attempt_count integer not null,
    cancelled_at datetime(6),
    complete_snapshot bit not null,
    created_at datetime(6) not null,
    created_by_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    declared_file_bytes bigint not null,
    declared_object_sha256 varchar(64) not null,
    declared_row_count bigint not null,
    fence_token bigint,
    format enum ('CSV') not null,
    idempotency_key_digest varchar(64) not null,
    lease_owner varchar(128),
    lease_until datetime(6),
    mode enum ('CHANGE_FEED','FULL_SNAPSHOT') not null,
    processed_row_count bigint not null,
    rejected_row_count bigint not null,
    request_digest varchar(64) not null,
    row_version bigint not null,
    schema_version varchar(32) not null,
    source_batch_id varchar(128) not null,
    source_system varchar(64) not null,
    status enum ('CANCELLED','CREATED','FAILED_RETRYABLE','PUBLISHED','READY_TO_PUBLISH','REJECTED','STAGING','UPLOADED','UPLOAD_PENDING','VALIDATING') not null,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    updated_at datetime(6) not null,
    upload_expires_at datetime(6),
    upload_object_key_digest varchar(64),
    upload_object_version varchar(256),
    primary key (id),
    KEY idx_td_bulk_import_tenant_created (tenant_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE td_bulk_import_scopes (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    cursor_end varchar(256),
    cursor_start varchar(256),
    declared_row_count bigint not null,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    scope_as_of datetime(6),
    source_sequence_end bigint,
    source_sequence_start bigint,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    primary key (id),
    KEY idx_td_bulk_import_scope_performance (tenant_id, event_id, performance_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE tenants (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    name varchar(160) not null,
    status varchar(24) not null,
    primary key (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE track1_purchase_ledger_batches (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    accepted_row_count integer not null,
    accepted_source_version varchar(128),
    complete_snapshot bit not null,
    created_at datetime(6) not null,
    created_by_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    idempotency_key_digest varchar(64) not null,
    input_row_count integer not null,
    input_type enum ('ACCOUNT_LIMIT_SNAPSHOT','INDIVIDUAL_TICKET') not null,
    ledger_version integer not null,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    quantity_basis varchar(128),
    reference_hmac_key_version varchar(32) not null,
    rejected_row_count integer not null,
    request_digest varchar(64) not null,
    row_version bigint not null,
    rows_digest varchar(64),
    source_as_of datetime(6) not null,
    source_version varchar(128) not null,
    status enum ('ACCEPTED','REJECTED') not null,
    synthetic_isolation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    track2_link_digest varchar(64),
    primary key (id),
    KEY idx_t1_ledger_batch_history (tenant_id, event_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE track1_purchase_ledger_errors (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    code varchar(64) not null,
    row_index integer,
    primary key (id),
    KEY idx_t1_ledger_error_batch (batch_id, row_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE track1_purchase_ledger_rows (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    account_ref_hmac varchar(64) not null,
    address_hmac varchar(64),
    batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    device_hmac varchar(64),
    ip_hmac varchar(64),
    payment_hmac varchar(64),
    purchased_at datetime(6) not null,
    quantity_unit enum ('ORDER_LINE','TICKET') not null,
    reservation_ref_hmac varchar(64) not null,
    source_as_of datetime(6) not null,
    source_row_index integer not null,
    source_version varchar(128) not null,
    ticket_quantity integer not null,
    ticket_ref_hmac varchar(64) not null,
    ticket_status enum ('ACTIVE','CANCELLED','REFUNDED','UNKNOWN') not null,
    track2_reservation_ref_hmac varchar(64),
    primary key (id),
    KEY idx_t1_ledger_row_batch_account (batch_id, account_ref_hmac)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE track1_purchase_limit_finding_reservations (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    effective_ticket_quantity integer not null,
    finding_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    reservation_ref_hmac varchar(64) not null,
    primary key (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE track1_purchase_limit_findings (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    account_ref_hmac varchar(64) not null,
    effective_ticket_quantity integer not null,
    max_tickets_per_account integer not null,
    policy_version varchar(128) not null,
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    primary key (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE track1_purchase_limit_run_clusters (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    cluster_id varchar(128) not null,
    member_count integer not null,
    risk_score float(53) not null,
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    primary key (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE track1_purchase_limit_runs (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    active_ledger_batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    algorithm varchar(32),
    algorithm_version varchar(160),
    analysis_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    analyzed_at datetime(6),
    cluster_count integer,
    completed_at datetime(6),
    created_at datetime(6) not null,
    created_by_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    error_code varchar(64),
    evaluation_reason varchar(64),
    evaluation_status varchar(64),
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    execution_status enum ('ANALYZER_FAILED','ANALYZER_UNAVAILABLE','INPUT_REJECTED','INTERRUPTED','IN_PROGRESS','SUCCEEDED') not null,
    idempotency_key_digest varchar(64) not null,
    ledger_batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    ledger_source_version varchar(128) not null,
    netted_excluded_row_count integer not null,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    policy_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    policy_version varchar(128),
    request_digest varchar(64) not null,
    row_version bigint not null,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    ledger_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    publication_outbox_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    active_ledger_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    primary key (id),
    KEY idx_t1_limit_run_scope_time (tenant_id, event_id, created_at),
    KEY idx_t1_limit_run_outbox (publication_outbox_id),
    UNIQUE KEY uk_t1_limit_run_active_version (active_ledger_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE track1_purchase_limit_shared_signal_observations (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    device_shared_account_count integer not null,
    distinct_account_count integer not null,
    distinct_reservation_count integer not null,
    excluded_from_louvain bit not null,
    ip_network_type varchar(64),
    observation_id varchar(128) not null,
    payment_shared_account_count integer not null,
    purchase_window_seconds integer,
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    scope_account_count integer not null,
    scope_id varchar(128) not null,
    scope_type varchar(64) not null,
    signal_type enum ('ADDRESS','IP') not null,
    ticket_quantity_status enum ('UNAVAILABLE','VERIFIED') not null,
    total_ticket_quantity integer,
    primary key (id),
    KEY idx_t1_signal_observation_run (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE verified_performances (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    performance_ref varchar(128) not null,
    revision integer not null,
    row_version bigint not null,
    source_version varchar(128) not null,
    status enum ('ACTIVE','WITHDRAWN') not null,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    verification_basis varchar(512) not null,
    verified_at datetime(6) not null,
    verified_by_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin not null,
    withdrawal_reason varchar(512),
    withdrawn_at datetime(6),
    withdrawn_by_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    primary key (id),
    KEY idx_verified_performances_scope_status (tenant_id, event_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE public_request_quota (
    scope VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    window_started_at DATETIME(6) NOT NULL,
    attempts INT NOT NULL,
    PRIMARY KEY (scope, actor_digest),
    CONSTRAINT chk_public_request_quota_attempts CHECK (attempts > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE td_bulk_import_staging_rows (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    import_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_row_no BIGINT NOT NULL,
    reservation_reference_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ticket_reference_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_reference_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    hmac_key_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    purchased_at DATETIME(6) NULL,
    ticket_quantity INT NOT NULL,
    source_event_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    source_changed_at DATETIME(6) NULL,
    change_action VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    row_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    quantity_unit VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ticket_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    source_version VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    source_sequence BIGINT NULL,
    payment_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    address_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    device_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ip_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    refund_account_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    phone_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    phone_source VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ip_network_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ip_network_type_source VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_td_bulk_staging_import_row (import_id, source_row_no),
    KEY idx_td_bulk_staging_scope (tenant_id, event_id, performance_id),
    CONSTRAINT chk_td_bulk_staging_values CHECK (source_row_no > 0 AND ticket_quantity > 0
        AND (change_action IS NULL OR change_action IN ('UPSERT','REMOVE','CANCEL','REFUND'))),
    CONSTRAINT chk_td_bulk_staging_v3_signals CHECK (
        (phone_source IS NULL) = (phone_hmac IS NULL)
        AND (phone_source IS NULL OR phone_source IN ('RECIPIENT','MEMBER'))
        AND (ip_network_type IS NULL OR ip_hmac IS NOT NULL)
        AND (ip_network_type IS NULL OR ip_network_type IN
             ('MOBILE_CGNAT','RESIDENTIAL','CORPORATE_EDU','DATACENTER_PROXY','UNKNOWN'))
        AND (ip_network_type_source IS NULL) = (ip_network_type IS NULL)
        AND (ip_network_type_source IS NULL OR ip_network_type_source IN ('DECLARED','CLASSIFIED','UNKNOWN'))
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE td_bulk_import_upload_deletions (
    import_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    trigger_reason VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    last_failure_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    last_attempt_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (import_id),
    KEY idx_td_bulk_upload_deletion_status (tenant_id, status),
    CONSTRAINT chk_td_bulk_upload_deletion_values CHECK (
        status IN ('DELETED','FAILED')
        AND attempt_count >= 0
        AND trigger_reason IN ('STAGING_COMPLETED','STAGING_REJECTED','RETENTION_EXPIRED')
        AND ((status = 'DELETED') = (deleted_at IS NOT NULL))
        AND (status <> 'FAILED' OR last_failure_code IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE td_bulk_ledger_candidate_imports (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    import_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    failure_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    preparation_attempt_count INT NOT NULL DEFAULT 0,
    preparation_fence_token BIGINT NOT NULL DEFAULT 0,
    preparation_owner VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    preparation_lease_until DATETIME(6) NULL,
    last_retry_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    source_object_version VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    source_object_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_td_bulk_candidate_import (import_id),
    KEY idx_td_bulk_candidate_import_tenant (tenant_id, status),
    KEY idx_td_bulk_candidate_prepare_lease (status, preparation_lease_until, updated_at),
    CONSTRAINT chk_td_bulk_candidate_import_state CHECK (
    (status IN ('PREPARING','READY','PUBLISHED') AND failure_code IS NULL) OR (status = 'REJECTED' AND failure_code IS NOT NULL)
)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE td_bulk_ledger_change_events (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_system VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    source_event_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    source_sequence BIGINT NOT NULL,
    source_changed_at DATETIME(6) NOT NULL,
    event_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_td_bulk_ledger_change_event (tenant_id, performance_id, source_system, source_event_id),
    UNIQUE KEY uk_td_bulk_ledger_change_sequence (tenant_id, performance_id, source_system, source_sequence),
    CONSTRAINT chk_td_bulk_ledger_change_sequence CHECK (source_sequence >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE td_bulk_ledger_publication_heads (
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    published_at DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id, performance_id),
    UNIQUE KEY uk_td_bulk_ledger_publication_version (version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE td_bulk_ledger_publication_history (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    publication_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    import_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    previous_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    generation_no BIGINT NOT NULL,
    revision_no BIGINT NOT NULL,
    source_system VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    source_batch_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    published_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_td_bulk_ledger_publication_history_scope (publication_id,performance_id),
    KEY idx_td_bulk_ledger_history_import (import_id,performance_id),
    CONSTRAINT chk_td_bulk_ledger_history_version CHECK (generation_no >= 0 AND revision_no >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE td_bulk_ledger_publication_outbox (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    publication_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    history_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    import_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    deduplication_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    delivery_attempt_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    lease_owner VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(6) NULL,
    fence_token BIGINT NOT NULL DEFAULT 0,
    last_error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_td_bulk_ledger_outbox_history (history_id),
    UNIQUE KEY uk_td_bulk_ledger_outbox_publication_scope (publication_id,performance_id),
    UNIQUE KEY uk_td_bulk_ledger_outbox_deduplication (deduplication_key),
    KEY idx_td_bulk_ledger_outbox_pending (status,created_at),
    CONSTRAINT chk_td_bulk_ledger_outbox_values CHECK (
        event_type = 'LEDGER_PUBLISHED'
        AND status IN ('PENDING','PROCESSING','DELIVERED','RETRYABLE','BLOCKED')
        AND delivery_attempt_count >= 0 AND fence_token >= 0
        AND ((status = 'PROCESSING') = (lease_owner IS NOT NULL AND lease_until IS NOT NULL))
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE td_bulk_ledger_publications (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    import_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    requester_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    published_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_td_bulk_ledger_publication_import (import_id),
    UNIQUE KEY uk_td_bulk_ledger_publication_idempotency (requester_membership_id,idempotency_key_digest)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE td_bulk_ledger_ticket_versions (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ticket_reference_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reservation_reference_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_reference_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    hmac_key_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    purchased_at DATETIME(6) NOT NULL,
    ticket_quantity INT NOT NULL,
    quantity_unit VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ticket_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_version VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    source_event_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_td_bulk_ledger_ticket_version (version_id, ticket_reference_hmac),
    KEY idx_td_bulk_ledger_ticket_lookup (tenant_id, ticket_reference_hmac),
    CONSTRAINT chk_td_bulk_ledger_ticket_values CHECK (ticket_quantity > 0 AND quantity_unit IN ('TICKET','ORDER_LINE') AND ticket_status IN ('ACTIVE','CANCELLED','REFUNDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE td_bulk_ledger_track1_analysis_jobs (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    outbox_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    failure_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_td_bl06_analysis_outbox (outbox_id),
    KEY idx_td_bl06_analysis_scope (tenant_id,event_id,performance_id,created_at),
    CONSTRAINT chk_td_bl06_analysis_state CHECK (
        status IN ('PENDING','PROCESSING','SUCCEEDED','RETRYABLE','BLOCKED') AND attempt_count >= 0
        AND ((status = 'SUCCEEDED' AND run_id IS NOT NULL AND failure_code IS NULL)
          OR (status IN ('RETRYABLE','BLOCKED') AND failure_code IS NOT NULL)
          OR (status IN ('PENDING','PROCESSING') AND failure_code IS NULL))
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE td_bulk_ledger_track1_bulk_clusters (
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    cluster_id VARCHAR(128) NOT NULL,
    risk_score DOUBLE NOT NULL,
    member_count INT NOT NULL,
    payment_hashes_json JSON NOT NULL,
    address_hashes_json JSON NOT NULL,
    device_hashes_json JSON NOT NULL,
    ip_hashes_json JSON NOT NULL,
    PRIMARY KEY(job_id,cluster_id),
    CONSTRAINT chk_bl07_cluster_values CHECK(risk_score >= 0 AND risk_score <= 1 AND member_count >= 2)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE td_bulk_ledger_track1_bulk_jobs (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    outbox_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ledger_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    artifact_id VARCHAR(128) NULL,
    object_key VARCHAR(128) NULL,
    object_version VARCHAR(128) NULL,
    artifact_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    record_count BIGINT NULL,
    source_version VARCHAR(128) NULL,
    source_as_of DATETIME(6) NULL,
    hmac_key_version VARCHAR(128) NULL,
    policy_version VARCHAR(128) NULL,
    data_job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    data_status VARCHAR(32) NULL,
    status VARCHAR(32) NOT NULL,
    failure_code VARCHAR(64) NULL,
    algorithm_version VARCHAR(160) NULL,
    shared_signal_observations_status VARCHAR(32) NULL,
    purchase_limit_evaluations_status VARCHAR(32) NULL,
    result_cursor VARCHAR(512) NULL,
    lease_owner VARCHAR(128) NULL,
    lease_until DATETIME(6) NULL,
    fence_token BIGINT NOT NULL DEFAULT 0,
    attempt_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    collection_phase VARCHAR(32) NULL,
    shared_signal_cursor VARCHAR(512) NULL,
    purchase_limit_evaluation_cursor VARCHAR(512) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_bl07_job_outbox (outbox_id),
    UNIQUE KEY uk_bl07_data_job (data_job_id),
    KEY idx_bl07_claim (status, lease_until, created_at),
    KEY idx_bl07_scope (tenant_id,event_id,performance_id,created_at),
    CONSTRAINT chk_bl07_counts CHECK (record_count IS NULL OR record_count >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE td_bulk_ledger_track1_bulk_limit_evaluations (
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    internal_event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    external_event_id VARCHAR(128) NOT NULL,
    evaluation_status VARCHAR(48) NOT NULL,
    policy_version VARCHAR(128) NULL,
    expected_finding_count INT NOT NULL,
    source_cursor VARCHAR(512) NULL,
    findings_cursor VARCHAR(512) NULL,
    findings_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    PRIMARY KEY(job_id,performance_id),
    KEY idx_bl07_limit_evaluation_status(job_id,findings_status),
    CONSTRAINT chk_bl07_limit_evaluation_status CHECK (evaluation_status IN ('EVALUATED','POLICY_NOT_CONFIRMED','QUANTITY_DATA_INCOMPLETE','POLICY_TIME_NOT_APPLICABLE')),
    CONSTRAINT chk_bl07_limit_evaluation_count CHECK (expected_finding_count >= 0),
    CONSTRAINT chk_bl07_limit_evaluation_collection CHECK (findings_status IN ('PENDING','COLLECTING','COMPLETED','NOT_APPLICABLE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE td_bulk_ledger_track1_bulk_limit_finding_reservations (
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_reference_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reservation_reference_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    effective_ticket_quantity BIGINT NOT NULL,
    PRIMARY KEY(job_id,performance_id,account_reference_hmac,reservation_reference_hmac),
    CONSTRAINT chk_bl07_limit_finding_reservation_quantity CHECK (effective_ticket_quantity >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE td_bulk_ledger_track1_bulk_limit_findings (
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_reference_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    effective_ticket_quantity BIGINT NOT NULL,
    max_tickets_per_account INT NOT NULL,
    policy_version VARCHAR(128) NOT NULL,
    PRIMARY KEY(job_id,performance_id,account_reference_hmac),
    CONSTRAINT chk_bl07_limit_finding_quantities CHECK (effective_ticket_quantity >= 1 AND max_tickets_per_account >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE td_bulk_ledger_track1_bulk_members (
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    cluster_id VARCHAR(128) NOT NULL,
    account_reference_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reservation_reference_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY(job_id,cluster_id,account_reference_hmac,reservation_reference_hmac)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE td_bulk_ledger_track1_bulk_shared_signal_observations (
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    observation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_cursor VARCHAR(512) NULL,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    scope_type VARCHAR(16) NOT NULL,
    signal_type VARCHAR(16) NOT NULL,
    distinct_account_count INT NOT NULL,
    scope_account_count INT NOT NULL,
    distinct_reservation_count INT NOT NULL,
    total_ticket_quantity BIGINT NULL,
    ticket_quantity_status VARCHAR(16) NOT NULL,
    payment_shared_account_count INT NOT NULL,
    device_shared_account_count INT NOT NULL,
    ip_network_type VARCHAR(32) NOT NULL,
    purchase_window_seconds BIGINT NULL,
    excluded_from_louvain BIT(1) NOT NULL,
    PRIMARY KEY(job_id,observation_id),
    KEY idx_bl07_shared_signal_scope(job_id,performance_id,signal_type),
    CONSTRAINT chk_bl07_shared_signal_values CHECK (distinct_account_count >= 2 AND scope_account_count >= 2
        AND distinct_reservation_count >= 1 AND (total_ticket_quantity IS NULL OR total_ticket_quantity >= 1)),
    CONSTRAINT chk_bl07_shared_signal_counts CHECK (payment_shared_account_count >= 0 AND device_shared_account_count >= 0
        AND (purchase_window_seconds IS NULL OR purchase_window_seconds >= 0)),
    CONSTRAINT chk_bl07_shared_signal_status CHECK (ticket_quantity_status IN ('VERIFIED','UNAVAILABLE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE td_bulk_ledger_track1_policy_snapshots (
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ledger_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_version VARCHAR(128) NULL,
    source_as_of DATETIME(6) NULL,
    ledger_complete_snapshot BIT(1) NOT NULL,
    policy_verification_status VARCHAR(32) NOT NULL,
    quantity_data_status VARCHAR(32) NOT NULL,
    data_completeness_reason VARCHAR(128) NULL,
    cancellation_handling VARCHAR(32) NOT NULL,
    duplicate_handling VARCHAR(32) NOT NULL,
    policy_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    policy_revision INT NULL,
    policy_version VARCHAR(128) NULL,
    max_tickets_per_account INT NULL,
    effective_from DATETIME(6) NULL,
    effective_until DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (job_id),
    UNIQUE KEY uk_bl07_policy_snapshot_id (snapshot_id),
    KEY idx_bl07_policy_snapshot_ledger (ledger_version_id),
    CONSTRAINT chk_bl07_policy_snapshot_state CHECK (
        (policy_verification_status='CONFIRMED' AND policy_id IS NOT NULL AND policy_revision >= 1
            AND policy_version IS NOT NULL AND max_tickets_per_account >= 1 AND effective_from IS NOT NULL)
        OR (policy_verification_status='POLICY_NOT_CONFIRMED' AND policy_id IS NULL AND policy_revision IS NULL
            AND policy_version IS NULL AND max_tickets_per_account IS NULL AND effective_from IS NULL AND effective_until IS NULL)
    ),
    CONSTRAINT chk_bl07_policy_snapshot_quantity CHECK (quantity_data_status IN ('COMPLETE','INCOMPLETE')),
    CONSTRAINT chk_bl07_policy_snapshot_times CHECK (effective_until IS NULL OR effective_until > effective_from),
    UNIQUE KEY uk_bl07_policy_snapshot_version_identity (snapshot_id, ledger_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE td_bulk_ledger_track1_ticket_signals (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ticket_reference_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    hmac_key_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    hmac_purpose VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payment_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    address_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    device_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ip_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    source_version VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    refund_account_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    phone_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ip_network_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    phone_source VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ip_network_type_source VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_td_bl06_signal_ticket (version_id,ticket_reference_hmac),
    CONSTRAINT chk_td_bl06_signal_values CHECK (
        hmac_purpose = 'TRACK1_CONNECTION'
        AND (payment_hmac IS NOT NULL OR address_hmac IS NOT NULL OR device_hmac IS NOT NULL OR ip_hmac IS NOT NULL
             OR refund_account_hmac IS NOT NULL OR phone_hmac IS NOT NULL)
        AND (ip_network_type IS NULL OR (ip_hmac IS NOT NULL AND ip_network_type IN
             ('MOBILE_CGNAT','RESIDENTIAL','CORPORATE_EDU','DATACENTER_PROXY','UNKNOWN')))
    ),
    CONSTRAINT chk_td_bl06_signal_sources CHECK (
        (phone_source IS NULL OR (phone_hmac IS NOT NULL AND phone_source IN ('RECIPIENT','MEMBER')))
        AND (ip_network_type_source IS NULL OR (ip_network_type IS NOT NULL
             AND ip_network_type_source IN ('DECLARED','CLASSIFIED','UNKNOWN')))
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE td_bulk_ledger_versions (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    candidate_import_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    import_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    generation_no BIGINT NOT NULL,
    revision_no BIGINT NOT NULL,
    base_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    source_system VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    source_batch_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    source_as_of DATETIME(6) NULL,
    source_cursor VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    source_sequence_end BIGINT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expected_head_known TINYINT(1) NOT NULL DEFAULT 0,
    expected_head_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_td_bulk_ledger_version_number (tenant_id, performance_id, generation_no, revision_no),
    UNIQUE KEY uk_td_bulk_ledger_version_import_scope (import_id, performance_id),
    KEY idx_td_bulk_ledger_version_base (base_version_id),
    CONSTRAINT chk_td_bulk_ledger_version_values CHECK (generation_no >= 0 AND revision_no >= 0 AND (source_sequence_end IS NULL OR source_sequence_end >= 0)),
    KEY idx_td_bulk_ledger_version_expected_head (expected_head_version_id),
    CONSTRAINT chk_td_bulk_ledger_version_expected_head CHECK (expected_head_known IN (0,1) AND (expected_head_known = 1 OR expected_head_version_id IS NULL)),
    UNIQUE KEY uk_td_bulk_ledger_version_scope_identity (id, tenant_id, event_id, performance_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE td_multi_event_investigation_candidates (
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    candidate_id CHAR(70) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    priority_band VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    investigation_score DOUBLE NOT NULL,
    baseline_max_score DOUBLE NULL,
    distinct_account_count INT NOT NULL,
    distinct_event_count INT NOT NULL,
    distinct_performance_count INT NOT NULL,
    verified_relationship_count INT NOT NULL,
    score_breakdown_json JSON NOT NULL,
    available_signals_json JSON NOT NULL,
    excluded_signals_json JSON NOT NULL,
    unavailable_signals_json JSON NOT NULL,
    normal_sharing_json JSON NOT NULL,
    PRIMARY KEY (run_id,candidate_id),
    KEY idx_mei_candidates_rank (run_id,investigation_score,candidate_id),
    CONSTRAINT chk_mei_candidate_values CHECK (
        priority_band IN ('HIGH','MEDIUM','LOW') AND investigation_score >= 0 AND investigation_score <= 1
        AND (baseline_max_score IS NULL OR (baseline_max_score >= 0 AND baseline_max_score <= 1))
        AND distinct_account_count >= 2 AND distinct_event_count >= 1 AND distinct_performance_count >= 2
        AND verified_relationship_count >= 1
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE td_multi_event_investigation_members (
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    candidate_id CHAR(70) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_reference_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (run_id,candidate_id,account_reference_hmac)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE td_multi_event_investigation_relationship_members (
    relationship_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    candidate_id CHAR(70) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_reference_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (relationship_id,account_reference_hmac),
    KEY idx_mei_relationship_member_candidate (run_id,candidate_id,account_reference_hmac)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE td_multi_event_investigation_relationship_sources (
    relationship_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (relationship_id,performance_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE td_multi_event_investigation_relationships (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    candidate_id CHAR(70) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    signal_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_count INT NOT NULL,
    source_scope_count INT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mei_relationship (run_id,candidate_id,signal_type,account_count,source_scope_count),
    KEY idx_mei_relationship_candidate (run_id,candidate_id),
    CONSTRAINT chk_mei_relationship_values CHECK (account_count >= 2 AND source_scope_count >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE td_multi_event_investigation_runs (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    requester_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ledger_version_set_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    supplier_namespace VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    hmac_purpose VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    hmac_key_version VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    score_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    projection_version VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    analysis_algorithm_version VARCHAR(256) CHARACTER SET ascii COLLATE ascii_bin NULL,
    analysis_as_of DATETIME(6) NOT NULL,
    analysis_start_at DATETIME(6) NOT NULL,
    analysis_end_at DATETIME(6) NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    failure_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_owner VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(6) NULL,
    fence_token BIGINT NOT NULL DEFAULT 0,
    attempt_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    data_job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    artifact_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    artifact_object_version VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL,
    artifact_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    artifact_record_count INT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mei_request (tenant_id,requester_membership_id,idempotency_key_digest),
    KEY idx_mei_tenant_created (tenant_id,created_at),
    KEY idx_mei_claim (status,lease_until,created_at),
    UNIQUE KEY uk_mei_data_job (data_job_id),
    CONSTRAINT chk_mei_artifact_binding CHECK (
        ((artifact_id IS NULL AND artifact_object_version IS NULL AND artifact_sha256 IS NULL
            AND artifact_record_count IS NULL AND data_job_id IS NULL)
         OR (artifact_id IS NOT NULL AND artifact_object_version IS NOT NULL
            AND artifact_sha256 REGEXP '^[0-9a-f]{64}$' AND artifact_record_count BETWEEN 1 AND 400000))
        AND (data_job_id IS NULL OR artifact_id IS NOT NULL)
    ),
    KEY idx_mei_data_claim (status, data_job_id, lease_until, created_at),
    CONSTRAINT chk_mei_run_state CHECK (
        hmac_purpose = 'TRACK1_CONNECTION'
        AND score_version IN ('score-v2-exp2-multi-performance-recurrence','score-v2-exp2-multi-event-recurrence')
        AND projection_version = 'projection-v2.2-exp2-ipwindow-softhub'
        AND analysis_end_at > analysis_start_at AND analysis_end_at <= analysis_as_of
        AND status IN ('PENDING','RUNNING','DATA_PENDING','RETRYABLE','FAILED','BLOCKED','COMPLETED')
        AND fence_token >= 0 AND attempt_count >= 0 AND row_version >= 0
        AND ((status = 'RUNNING') = (lease_owner IS NOT NULL AND lease_until IS NOT NULL))
        AND ((status = 'COMPLETED') = (completed_at IS NOT NULL))
        AND ((status = 'COMPLETED') = (analysis_algorithm_version IS NOT NULL))
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE td_multi_event_investigation_scopes (
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ledger_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    external_event_ref VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    source_batch_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    policy_snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY (run_id,performance_id),
    UNIQUE KEY uk_mei_scope_version (run_id,ledger_version_id),
    KEY idx_mei_scope_event (tenant_id,event_id,performance_id),
    KEY idx_mei_scope_policy_snapshot (policy_snapshot_id),
    KEY idx_mei_scope_version_identity (ledger_version_id, tenant_id, event_id, performance_id),
    KEY idx_mei_scope_policy_snapshot_version (policy_snapshot_id, ledger_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Current Entity indexes and UNIQUE constraints, reconciled per table.
ALTER TABLE approval_decisions ADD constraint uk_approval_decision_request unique (approval_request_id);
ALTER TABLE approval_decisions ADD constraint uk_approval_decision_receipt unique (review_receipt_id);
ALTER TABLE approval_decisions ADD constraint uk_approval_decision_scope_id unique (tenant_id, event_id, case_id, id);
ALTER TABLE approval_requests ADD constraint uk_approval_request_report unique (report_version_id);
ALTER TABLE approval_requests ADD constraint uk_approval_request_active_case unique (tenant_id, active_case_id);
ALTER TABLE approval_requests ADD constraint uk_approval_request_scope_id unique (tenant_id, event_id, case_id, id);
ALTER TABLE case_idempotency_records ADD constraint uk_case_idempotency_scope_key unique (tenant_id, actor_membership_id, operation, target_case_id, key_digest);
ALTER TABLE case_source_references ADD constraint uk_case_sources_case_key unique (case_id, source_key_digest);
ALTER TABLE cases ADD constraint uk_cases_scope_id unique (tenant_id, event_id, id);
ALTER TABLE events ADD constraint uk_events_tenant_external unique (tenant_id, external_event_ref);
ALTER TABLE events ADD constraint idx_events_tenant_id unique (tenant_id, id);
ALTER TABLE evidence_assets ADD constraint uk_evidence_assets_object_key unique (object_key);
ALTER TABLE evidence_preservation_policies ADD constraint uk_preservation_policy_version unique (policy_id, policy_version);
ALTER TABLE evidence_preservation_records ADD constraint uk_preservation_object_policy unique (tenant_id, evidence_id, storage_profile, actual_version_id, preservation_policy_id, preservation_policy_version);
ALTER TABLE evidence_snapshots ADD constraint uk_evidence_snapshots_case_revision unique (case_id, evidence_revision);
ALTER TABLE evidence_snapshots ADD constraint uk_evidence_snapshots_scope_id unique (tenant_id, event_id, case_id, id);
ALTER TABLE fan_report_performance_links ADD constraint uk_fan_report_performance_link_active unique (active_fan_report_id);
ALTER TABLE fan_report_performance_links ADD constraint uk_fan_report_performance_link_scope_id unique (tenant_id, event_id, id);
ALTER TABLE fan_reports ADD constraint uk_fan_reports_receipt unique (receipt_number);
ALTER TABLE fan_reports ADD constraint uk_fan_reports_idempotency unique (idempotency_key_digest);
ALTER TABLE listing_candidate_match_runs ADD constraint uk_listing_candidate_run_idempotency unique (created_by_membership_id, analysis_id, idempotency_key_digest);
ALTER TABLE listing_candidate_match_runs ADD constraint uk_listing_candidate_run_active_analysis unique (active_analysis_id);
ALTER TABLE listing_candidate_match_runs ADD constraint uk_listing_candidate_run_scope_id unique (tenant_id, event_id, id);
ALTER TABLE listing_context_analyses ADD constraint uk_listing_analyses_request unique (request_id);
ALTER TABLE listing_context_analyses ADD constraint uk_listing_analyses_active_evidence unique (active_evidence_id);
ALTER TABLE listing_context_analyses ADD constraint uk_listing_analyses_idempotency unique (created_by_membership_id, evidence_id, idempotency_key_digest);
ALTER TABLE listing_context_observations ADD constraint uk_listing_observations_field unique (analysis_id, field_name);
ALTER TABLE macro_audit_evidence ADD constraint uk_macro_audit_evidence_key unique (evidence_key);
ALTER TABLE membership_events ADD constraint uk_membership_events_member_event unique (membership_id, event_id);
ALTER TABLE membership_roles ADD constraint uk_membership_roles_member_role unique (membership_id, role);
ALTER TABLE memberships ADD constraint uk_memberships_tenant_subject unique (tenant_id, subject);
ALTER TABLE memberships ADD constraint idx_memberships_tenant_id unique (tenant_id, id);
ALTER TABLE official_pdf_artifacts ADD constraint uk_official_pdf_artifact_issuance unique (issuance_id);
ALTER TABLE official_pdf_artifacts ADD constraint uk_official_pdf_artifact_decision unique (approval_decision_id);
ALTER TABLE official_pdf_artifacts ADD constraint uk_official_pdf_artifact_scope_id unique (tenant_id, event_id, case_id, id);
ALTER TABLE official_pdf_issuance_attempts ADD constraint uk_official_pdf_attempt_number unique (issuance_id, attempt_number);
ALTER TABLE official_pdf_issuances ADD constraint uk_official_pdf_issuance_decision unique (approval_decision_id);
ALTER TABLE official_pdf_issuances ADD constraint uk_official_pdf_issuance_scope_id unique (tenant_id, event_id, case_id, id);
ALTER TABLE official_pdf_issuances ADD constraint uk_official_pdf_issuance_scope_decision unique (tenant_id, event_id, case_id, id, approval_decision_id);
ALTER TABLE performance_link_audits ADD constraint uk_performance_link_audit_idempotency unique (actor_membership_id, fan_report_id, idempotency_key_digest);
ALTER TABLE performance_verification_audits ADD constraint uk_performance_verification_audit_revision unique (performance_id, revision);
ALTER TABLE purchase_limit_policies ADD constraint uk_purchase_limit_policy_active_scope_key unique (tenant_id, event_id, active_scope_key);
ALTER TABLE purchase_limit_policies ADD constraint uk_purchase_limit_policy_version_scope unique (tenant_id, event_id, performance_id, policy_version);
ALTER TABLE purchase_limit_policy_audits ADD constraint uk_purchase_limit_policy_audit_revision unique (policy_id, revision);
ALTER TABLE report_versions ADD constraint uk_report_versions_case_revision unique (case_id, revision);
ALTER TABLE reservation_candidates ADD constraint uk_reservation_candidate_reference_seat unique (batch_id, reservation_ref_hmac, zone, row_num, seat_num);
ALTER TABLE reservation_candidates ADD constraint uk_reservation_candidate_reference_ticket unique (batch_id, reservation_ref_hmac, ticket_ref_hmac);
ALTER TABLE reservation_data_sources ADD constraint uk_reservation_data_source_scope unique (tenant_id, event_id, performance_id);
ALTER TABLE reservation_data_sources ADD constraint uk_reservation_data_source_scope_id unique (tenant_id, event_id, id);
ALTER TABLE reservation_import_batches ADD constraint uk_reservation_import_batch_idempotency unique (created_by_membership_id, event_id, idempotency_key_digest);
ALTER TABLE reservation_import_batches ADD constraint uk_reservation_import_batch_source_version unique (tenant_id, event_id, performance_id, source_version);
ALTER TABLE reservation_import_batches ADD constraint uk_reservation_import_batch_scope_id unique (tenant_id, event_id, id);
ALTER TABLE reservation_import_errors ADD constraint uk_reservation_import_error unique (batch_id, row_index, code);
ALTER TABLE review_receipts ADD constraint uk_review_receipt_token unique (token_sha256);
ALTER TABLE review_receipts ADD constraint uk_review_receipt_active unique (tenant_id, approver_membership_id, active_approval_request_id);
ALTER TABLE review_receipts ADD constraint uk_review_receipt_scope_id unique (tenant_id, event_id, case_id, id);
ALTER TABLE service_tenant_bindings ADD constraint uk_service_tenant_identity unique (issuer, client_id, subject);
ALTER TABLE td_bulk_import_jobs ADD constraint uk_td_bulk_import_idempotency unique (created_by_membership_id, idempotency_key_digest);
ALTER TABLE td_bulk_import_jobs ADD constraint uk_td_bulk_import_source_batch unique (tenant_id, source_system, source_batch_id);
ALTER TABLE td_bulk_import_jobs ADD constraint uk_td_bulk_import_job_tenant unique (id, tenant_id);
ALTER TABLE td_bulk_import_scopes ADD constraint uk_td_bulk_import_scope unique (job_id, event_id, performance_id);
ALTER TABLE track1_purchase_ledger_batches ADD constraint uk_t1_ledger_batch_idempotency unique (created_by_membership_id, event_id, idempotency_key_digest);
ALTER TABLE track1_purchase_ledger_batches ADD constraint uk_t1_ledger_batch_source unique (tenant_id, event_id, performance_id, accepted_source_version);
ALTER TABLE track1_purchase_ledger_batches ADD constraint uk_t1_ledger_batch_version unique (tenant_id, event_id, performance_id, ledger_version);
ALTER TABLE track1_purchase_ledger_rows ADD constraint uk_t1_ledger_row_ticket unique (batch_id, ticket_ref_hmac);
ALTER TABLE track1_purchase_ledger_rows ADD constraint uk_t1_ledger_row_index unique (batch_id, source_row_index);
ALTER TABLE track1_purchase_limit_finding_reservations ADD constraint uk_t1_limit_finding_reservation unique (finding_id, reservation_ref_hmac);
ALTER TABLE track1_purchase_limit_findings ADD constraint uk_t1_limit_finding_run_account unique (run_id, account_ref_hmac);
ALTER TABLE track1_purchase_limit_run_clusters ADD constraint uk_t1_limit_run_cluster unique (run_id, cluster_id);
ALTER TABLE track1_purchase_limit_runs ADD constraint uk_t1_limit_run_idempotency unique (created_by_membership_id, event_id, idempotency_key_digest);
ALTER TABLE track1_purchase_limit_runs ADD constraint uk_t1_limit_run_active_batch unique (active_ledger_batch_id);
ALTER TABLE track1_purchase_limit_runs ADD constraint uk_t1_limit_run_request unique (analysis_request_id);
ALTER TABLE track1_purchase_limit_shared_signal_observations ADD constraint uk_t1_signal_observation_run_id unique (run_id, observation_id);
ALTER TABLE verified_performances ADD constraint uk_verified_performances_scope_ref unique (tenant_id, event_id, performance_ref);
ALTER TABLE verified_performances ADD constraint uk_verified_performances_scope_id unique (tenant_id, event_id, id);



-- Add reservation scope integrity after events and all supporting indexes exist.
ALTER TABLE reservation
    ADD CONSTRAINT fk_reservation_scope FOREIGN KEY (tenant_id, event_id) REFERENCES events (tenant_id, id),
    ADD CONSTRAINT chk_reservation_scope CHECK (
        (tenant_id IS NULL AND event_id IS NULL AND performance_ref IS NULL)
        OR (tenant_id IS NOT NULL AND event_id IS NOT NULL AND performance_ref IS NOT NULL
            AND CHAR_LENGTH(TRIM(performance_ref)) > 0)
    );


ALTER TABLE approval_audit_events ADD CONSTRAINT fk_approval_audit_request FOREIGN KEY
        (tenant_id, event_id, case_id, approval_request_id)
        REFERENCES approval_requests (tenant_id, event_id, case_id, id);
ALTER TABLE approval_audit_events ADD CONSTRAINT fk_approval_audit_report FOREIGN KEY
        (tenant_id, event_id, case_id, report_version_id)
        REFERENCES report_versions (tenant_id, event_id, case_id, id);
ALTER TABLE approval_audit_events ADD CONSTRAINT fk_approval_audit_snapshot FOREIGN KEY
        (tenant_id, event_id, case_id, evidence_snapshot_id)
        REFERENCES evidence_snapshots (tenant_id, event_id, case_id, id);
ALTER TABLE approval_audit_events ADD CONSTRAINT fk_approval_audit_actor FOREIGN KEY (tenant_id, actor_membership_id)
        REFERENCES memberships (tenant_id, id);
ALTER TABLE approval_audit_events ADD CONSTRAINT fk_approval_audit_receipt FOREIGN KEY
        (tenant_id, event_id, case_id, review_receipt_id)
        REFERENCES review_receipts (tenant_id, event_id, case_id, id);
ALTER TABLE approval_audit_events ADD CONSTRAINT fk_approval_audit_decision FOREIGN KEY
        (tenant_id, event_id, case_id, approval_decision_id)
        REFERENCES approval_decisions (tenant_id, event_id, case_id, id);
ALTER TABLE approval_decisions ADD CONSTRAINT fk_approval_decision_request FOREIGN KEY
        (tenant_id, event_id, case_id, approval_request_id)
        REFERENCES approval_requests (tenant_id, event_id, case_id, id);
ALTER TABLE approval_decisions ADD CONSTRAINT fk_approval_decision_receipt FOREIGN KEY
        (tenant_id, event_id, case_id, review_receipt_id)
        REFERENCES review_receipts (tenant_id, event_id, case_id, id);
ALTER TABLE approval_decisions ADD CONSTRAINT fk_approval_decision_report FOREIGN KEY
        (tenant_id, event_id, case_id, report_version_id)
        REFERENCES report_versions (tenant_id, event_id, case_id, id);
ALTER TABLE approval_decisions ADD CONSTRAINT fk_approval_decision_snapshot FOREIGN KEY
        (tenant_id, event_id, case_id, evidence_snapshot_id)
        REFERENCES evidence_snapshots (tenant_id, event_id, case_id, id);
ALTER TABLE approval_decisions ADD CONSTRAINT fk_approval_decision_actor FOREIGN KEY (tenant_id, decided_by_membership_id)
        REFERENCES memberships (tenant_id, id);
ALTER TABLE approval_requests ADD CONSTRAINT fk_approval_request_case FOREIGN KEY (tenant_id, event_id, case_id)
        REFERENCES cases (tenant_id, event_id, id);
ALTER TABLE approval_requests ADD CONSTRAINT fk_approval_request_report FOREIGN KEY
        (tenant_id, event_id, case_id, report_version_id)
        REFERENCES report_versions (tenant_id, event_id, case_id, id);
ALTER TABLE approval_requests ADD CONSTRAINT fk_approval_request_snapshot FOREIGN KEY
        (tenant_id, event_id, case_id, evidence_snapshot_id)
        REFERENCES evidence_snapshots (tenant_id, event_id, case_id, id);
ALTER TABLE approval_requests ADD CONSTRAINT fk_approval_request_actor FOREIGN KEY (tenant_id, requested_by_membership_id)
        REFERENCES memberships (tenant_id, id);
ALTER TABLE case_activities ADD CONSTRAINT fk_case_activities_case FOREIGN KEY (tenant_id, event_id, case_id)
        REFERENCES cases (tenant_id, event_id, id);
ALTER TABLE case_activities ADD CONSTRAINT fk_case_activities_actor FOREIGN KEY (tenant_id, actor_membership_id)
        REFERENCES memberships (tenant_id, id);
ALTER TABLE case_activities ADD CONSTRAINT fk_case_activities_source FOREIGN KEY (case_id, source_reference_id)
        REFERENCES case_source_references (case_id, id);
ALTER TABLE case_idempotency_records ADD CONSTRAINT fk_case_idempotency_actor FOREIGN KEY (tenant_id, actor_membership_id)
        REFERENCES memberships (tenant_id, id);
ALTER TABLE case_idempotency_records ADD CONSTRAINT fk_case_idempotency_result FOREIGN KEY (tenant_id, result_case_id)
        REFERENCES cases (tenant_id, id);
ALTER TABLE case_source_references ADD CONSTRAINT fk_case_sources_case FOREIGN KEY (tenant_id, event_id, case_id)
        REFERENCES cases (tenant_id, event_id, id);
ALTER TABLE case_source_references ADD CONSTRAINT fk_case_sources_report FOREIGN KEY (tenant_id, event_id, fan_report_id)
        REFERENCES fan_reports (tenant_id, event_id, id);
ALTER TABLE case_source_references ADD CONSTRAINT fk_case_sources_asset FOREIGN KEY (fan_report_id, evidence_id)
        REFERENCES evidence_assets (fan_report_id, id);
ALTER TABLE case_source_references ADD CONSTRAINT fk_case_sources_graph FOREIGN KEY (tenant_id, event_id, anchor_audit_id)
        REFERENCES macro_audit_evidence (tenant_id, internal_event_id, audit_id);
ALTER TABLE case_source_references ADD CONSTRAINT fk_case_sources_candidate_run FOREIGN KEY (tenant_id, event_id, listing_candidate_match_run_id)
        REFERENCES listing_candidate_match_runs (tenant_id, event_id, id);
ALTER TABLE cases ADD CONSTRAINT fk_cases_event FOREIGN KEY (tenant_id, event_id) REFERENCES events (tenant_id, id);
ALTER TABLE cases ADD CONSTRAINT fk_cases_creator FOREIGN KEY (tenant_id, created_by_membership_id)
        REFERENCES memberships (tenant_id, id);
ALTER TABLE cases ADD CONSTRAINT fk_cases_assignee FOREIGN KEY (tenant_id, assignee_membership_id)
        REFERENCES memberships (tenant_id, id);
ALTER TABLE cases ADD CONSTRAINT fk_cases_related FOREIGN KEY (tenant_id, event_id, related_case_id)
        REFERENCES cases (tenant_id, event_id, id);
ALTER TABLE cases ADD CONSTRAINT fk_cases_current_report FOREIGN KEY
        (tenant_id, event_id, id, current_report_version_id)
        REFERENCES report_versions (tenant_id, event_id, case_id, id);
ALTER TABLE events ADD CONSTRAINT fk_events_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id);
ALTER TABLE evidence_access_audit_events ADD CONSTRAINT fk_evidence_access_audit_report FOREIGN KEY (tenant_id, event_id, fan_report_id)
        REFERENCES fan_reports (tenant_id, event_id, id);
ALTER TABLE evidence_access_audit_events ADD CONSTRAINT fk_evidence_access_audit_asset FOREIGN KEY (fan_report_id, evidence_id)
        REFERENCES evidence_assets (fan_report_id, id);
ALTER TABLE evidence_access_audit_events ADD CONSTRAINT fk_evidence_access_audit_actor FOREIGN KEY (tenant_id, actor_membership_id)
        REFERENCES memberships (tenant_id, id);
ALTER TABLE evidence_assets ADD CONSTRAINT fk_evidence_assets_report FOREIGN KEY (fan_report_id) REFERENCES fan_reports (id);
ALTER TABLE evidence_preservation_attempts ADD CONSTRAINT fk_preservation_attempt_case FOREIGN KEY (tenant_id, event_id, case_id)
        REFERENCES cases (tenant_id, event_id, id);
ALTER TABLE evidence_preservation_attempts ADD CONSTRAINT fk_preservation_attempt_snapshot FOREIGN KEY
        (tenant_id, event_id, case_id, evidence_snapshot_id)
        REFERENCES evidence_snapshots (tenant_id, event_id, case_id, id);
ALTER TABLE evidence_preservation_attempts ADD CONSTRAINT fk_preservation_attempt_source FOREIGN KEY
        (case_id, source_reference_id, evidence_id)
        REFERENCES case_source_references (case_id, id, evidence_id);
ALTER TABLE evidence_preservation_attempts ADD CONSTRAINT fk_preservation_attempt_asset FOREIGN KEY (evidence_id)
        REFERENCES evidence_assets (id);
ALTER TABLE evidence_preservation_attempts ADD CONSTRAINT fk_preservation_attempt_verification FOREIGN KEY
        (tenant_id, event_id, case_id, evidence_snapshot_id, source_reference_id,
            evidence_id, verification_record_id)
        REFERENCES evidence_verification_records
            (tenant_id, event_id, case_id, evidence_snapshot_id, source_reference_id,
                evidence_id, id);
ALTER TABLE evidence_preservation_attempts ADD CONSTRAINT fk_preservation_attempt_record FOREIGN KEY (preservation_record_id)
        REFERENCES evidence_preservation_records (id);
ALTER TABLE evidence_preservation_attempts ADD CONSTRAINT fk_preservation_attempt_actor FOREIGN KEY (tenant_id, actor_membership_id)
        REFERENCES memberships (tenant_id, id);
ALTER TABLE evidence_preservation_policies ADD CONSTRAINT fk_preservation_policy_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id);
ALTER TABLE evidence_preservation_policies ADD CONSTRAINT fk_preservation_policy_event FOREIGN KEY (tenant_id, event_id)
        REFERENCES events (tenant_id, id);
ALTER TABLE evidence_preservation_records ADD CONSTRAINT fk_preservation_record_case FOREIGN KEY (tenant_id, event_id, case_id)
        REFERENCES cases (tenant_id, event_id, id);
ALTER TABLE evidence_preservation_records ADD CONSTRAINT fk_preservation_record_snapshot FOREIGN KEY
        (tenant_id, event_id, case_id, evidence_snapshot_id)
        REFERENCES evidence_snapshots (tenant_id, event_id, case_id, id);
ALTER TABLE evidence_preservation_records ADD CONSTRAINT fk_preservation_record_source FOREIGN KEY
        (case_id, source_reference_id, evidence_id)
        REFERENCES case_source_references (case_id, id, evidence_id);
ALTER TABLE evidence_preservation_records ADD CONSTRAINT fk_preservation_record_asset FOREIGN KEY (evidence_id)
        REFERENCES evidence_assets (id);
ALTER TABLE evidence_preservation_records ADD CONSTRAINT fk_preservation_record_verification FOREIGN KEY
        (tenant_id, event_id, case_id, evidence_snapshot_id, source_reference_id,
            evidence_id, verification_record_id)
        REFERENCES evidence_verification_records
            (tenant_id, event_id, case_id, evidence_snapshot_id, source_reference_id,
                evidence_id, id);
ALTER TABLE evidence_preservation_records ADD CONSTRAINT fk_preservation_record_policy FOREIGN KEY
        (tenant_id, preservation_policy_id, preservation_policy_version)
        REFERENCES evidence_preservation_policies (tenant_id, policy_id, policy_version);
ALTER TABLE evidence_preservation_records ADD CONSTRAINT fk_preservation_record_actor FOREIGN KEY (tenant_id, actor_membership_id)
        REFERENCES memberships (tenant_id, id);
ALTER TABLE evidence_snapshots ADD CONSTRAINT fk_evidence_snapshots_case FOREIGN KEY (tenant_id, event_id, case_id)
        REFERENCES cases (tenant_id, event_id, id);
ALTER TABLE evidence_snapshots ADD CONSTRAINT fk_evidence_snapshots_actor FOREIGN KEY (tenant_id, created_by_membership_id)
        REFERENCES memberships (tenant_id, id);
ALTER TABLE evidence_verification_records ADD CONSTRAINT fk_evidence_verifications_snapshot FOREIGN KEY (tenant_id, event_id, case_id, evidence_snapshot_id)
        REFERENCES evidence_snapshots (tenant_id, event_id, case_id, id);
ALTER TABLE evidence_verification_records ADD CONSTRAINT fk_evidence_verifications_source FOREIGN KEY (case_id, source_reference_id)
        REFERENCES case_source_references (case_id, id);
ALTER TABLE evidence_verification_records ADD CONSTRAINT fk_evidence_verifications_report FOREIGN KEY (tenant_id, event_id, fan_report_id)
        REFERENCES fan_reports (tenant_id, event_id, id);
ALTER TABLE evidence_verification_records ADD CONSTRAINT fk_evidence_verifications_asset FOREIGN KEY (fan_report_id, evidence_id)
        REFERENCES evidence_assets (fan_report_id, id);
ALTER TABLE evidence_verification_records ADD CONSTRAINT fk_evidence_verifications_actor FOREIGN KEY (tenant_id, created_by_membership_id)
        REFERENCES memberships (tenant_id, id);
ALTER TABLE fan_report_performance_links ADD CONSTRAINT fk_fan_report_performance_link_report FOREIGN KEY (tenant_id, event_id, fan_report_id)
        REFERENCES fan_reports (tenant_id, event_id, id);
ALTER TABLE fan_report_performance_links ADD CONSTRAINT fk_fan_report_performance_link_performance FOREIGN KEY (tenant_id, event_id, performance_id)
        REFERENCES verified_performances (tenant_id, event_id, id);
ALTER TABLE fan_report_performance_links ADD CONSTRAINT fk_fan_report_performance_link_actor FOREIGN KEY (tenant_id, linked_by_membership_id)
        REFERENCES memberships (tenant_id, id);
ALTER TABLE fan_report_performance_links ADD CONSTRAINT fk_fan_report_performance_link_ended_actor FOREIGN KEY (tenant_id, ended_by_membership_id)
        REFERENCES memberships (tenant_id, id);
ALTER TABLE fan_reports ADD CONSTRAINT fk_fan_reports_event_scope FOREIGN KEY (tenant_id, event_id)
        REFERENCES events (tenant_id, id);
ALTER TABLE issuance_holds ADD CONSTRAINT fk_issuance_hold_case FOREIGN KEY (tenant_id, event_id, case_id)
        REFERENCES cases (tenant_id, event_id, id);
ALTER TABLE issuance_holds ADD CONSTRAINT fk_issuance_hold_actor FOREIGN KEY (tenant_id, created_by_membership_id)
        REFERENCES memberships (tenant_id, id);
ALTER TABLE listing_candidate_match_runs ADD CONSTRAINT fk_listing_candidate_run_analysis FOREIGN KEY (tenant_id, event_id, analysis_id)
        REFERENCES listing_context_analyses (tenant_id, event_id, id);
ALTER TABLE listing_candidate_match_runs ADD CONSTRAINT fk_listing_candidate_run_report FOREIGN KEY (tenant_id, event_id, fan_report_id)
        REFERENCES fan_reports (tenant_id, event_id, id);
ALTER TABLE listing_candidate_match_runs ADD CONSTRAINT fk_listing_candidate_run_asset FOREIGN KEY (fan_report_id, evidence_id)
        REFERENCES evidence_assets (fan_report_id, id);
ALTER TABLE listing_candidate_match_runs ADD CONSTRAINT fk_listing_candidate_run_actor FOREIGN KEY (tenant_id, created_by_membership_id)
        REFERENCES memberships (tenant_id, id);
ALTER TABLE listing_candidate_match_runs ADD CONSTRAINT fk_listing_candidate_run_link FOREIGN KEY (performance_link_id)
        REFERENCES fan_report_performance_links (id);
ALTER TABLE listing_candidate_match_runs ADD CONSTRAINT fk_listing_candidate_run_performance FOREIGN KEY (verified_performance_id)
        REFERENCES verified_performances (id);
ALTER TABLE listing_candidate_match_runs ADD CONSTRAINT fk_listing_candidate_run_source FOREIGN KEY (reservation_source_id)
        REFERENCES reservation_data_sources (id);
ALTER TABLE listing_candidate_match_runs ADD CONSTRAINT fk_listing_candidate_run_batch FOREIGN KEY (reservation_batch_id)
        REFERENCES reservation_import_batches (id);
ALTER TABLE listing_candidate_match_runs ADD CONSTRAINT fk_listing_candidate_run_candidate FOREIGN KEY (candidate_id) REFERENCES reservation_candidates (id);
ALTER TABLE listing_context_analyses ADD CONSTRAINT fk_listing_analyses_report FOREIGN KEY (tenant_id, event_id, fan_report_id)
        REFERENCES fan_reports (tenant_id, event_id, id);
ALTER TABLE listing_context_analyses ADD CONSTRAINT fk_listing_analyses_asset FOREIGN KEY (fan_report_id, evidence_id)
        REFERENCES evidence_assets (fan_report_id, id);
ALTER TABLE listing_context_analyses ADD CONSTRAINT fk_listing_analyses_actor FOREIGN KEY (tenant_id, created_by_membership_id)
        REFERENCES memberships (tenant_id, id);
ALTER TABLE listing_context_observations ADD CONSTRAINT fk_listing_observations_analysis FOREIGN KEY (analysis_id) REFERENCES listing_context_analyses (id);
ALTER TABLE macro_audit_evidence ADD CONSTRAINT fk_macro_audit_evidence_ticket_audit
    FOREIGN KEY (audit_id) REFERENCES ticket_audit (id);
ALTER TABLE macro_audit_evidence ADD CONSTRAINT fk_macro_audit_evidence_event_scope
        FOREIGN KEY (tenant_id, internal_event_id) REFERENCES events (tenant_id, id);
ALTER TABLE membership_events ADD CONSTRAINT fk_membership_events_member_scope
        FOREIGN KEY (tenant_id, membership_id) REFERENCES memberships (tenant_id, id);
ALTER TABLE membership_events ADD CONSTRAINT fk_membership_events_event_scope
        FOREIGN KEY (tenant_id, event_id) REFERENCES events (tenant_id, id);
ALTER TABLE membership_roles ADD CONSTRAINT fk_membership_roles_member FOREIGN KEY (membership_id) REFERENCES memberships (id);
ALTER TABLE memberships ADD CONSTRAINT fk_memberships_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id);
ALTER TABLE official_pdf_artifacts ADD CONSTRAINT fk_official_pdf_artifact_issuance FOREIGN KEY
        (tenant_id, event_id, case_id, issuance_id, approval_decision_id)
        REFERENCES official_pdf_issuances (tenant_id, event_id, case_id, id, approval_decision_id);
ALTER TABLE official_pdf_artifacts ADD CONSTRAINT fk_official_pdf_artifact_decision FOREIGN KEY
        (tenant_id, event_id, case_id, approval_decision_id)
        REFERENCES approval_decisions (tenant_id, event_id, case_id, id);
ALTER TABLE official_pdf_audit_events ADD CONSTRAINT fk_official_pdf_audit_issuance FOREIGN KEY
        (tenant_id, event_id, case_id, issuance_id)
        REFERENCES official_pdf_issuances (tenant_id, event_id, case_id, id);
ALTER TABLE official_pdf_audit_events ADD CONSTRAINT fk_official_pdf_audit_artifact FOREIGN KEY
        (tenant_id, event_id, case_id, artifact_id)
        REFERENCES official_pdf_artifacts (tenant_id, event_id, case_id, id);
ALTER TABLE official_pdf_audit_events ADD CONSTRAINT fk_official_pdf_audit_actor FOREIGN KEY (tenant_id, actor_membership_id)
        REFERENCES memberships (tenant_id, id);
ALTER TABLE official_pdf_issuance_attempts ADD CONSTRAINT fk_official_pdf_attempt_issuance FOREIGN KEY (issuance_id)
        REFERENCES official_pdf_issuances (id);
ALTER TABLE official_pdf_issuances ADD CONSTRAINT fk_official_pdf_issuance_decision FOREIGN KEY
        (tenant_id, event_id, case_id, approval_decision_id)
        REFERENCES approval_decisions (tenant_id, event_id, case_id, id);
ALTER TABLE official_pdf_issuances ADD CONSTRAINT fk_official_pdf_issuance_request FOREIGN KEY
        (tenant_id, event_id, case_id, approval_request_id)
        REFERENCES approval_requests (tenant_id, event_id, case_id, id);
ALTER TABLE official_pdf_issuances ADD CONSTRAINT fk_official_pdf_issuance_receipt FOREIGN KEY
        (tenant_id, event_id, case_id, review_receipt_id)
        REFERENCES review_receipts (tenant_id, event_id, case_id, id);
ALTER TABLE official_pdf_issuances ADD CONSTRAINT fk_official_pdf_issuance_report FOREIGN KEY
        (tenant_id, event_id, case_id, report_version_id)
        REFERENCES report_versions (tenant_id, event_id, case_id, id);
ALTER TABLE official_pdf_issuances ADD CONSTRAINT fk_official_pdf_issuance_snapshot FOREIGN KEY
        (tenant_id, event_id, case_id, evidence_snapshot_id)
        REFERENCES evidence_snapshots (tenant_id, event_id, case_id, id);
ALTER TABLE official_pdf_issuances ADD CONSTRAINT fk_official_pdf_issuance_actor FOREIGN KEY (tenant_id, requested_by_membership_id)
        REFERENCES memberships (tenant_id, id);
ALTER TABLE official_pdf_synthetic_tenants ADD CONSTRAINT fk_official_pdf_synthetic_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id);
ALTER TABLE performance_link_audits ADD CONSTRAINT fk_performance_link_audit_link FOREIGN KEY (link_id) REFERENCES fan_report_performance_links (id);
ALTER TABLE performance_link_audits ADD CONSTRAINT fk_performance_link_audit_actor FOREIGN KEY (tenant_id, actor_membership_id)
        REFERENCES memberships (tenant_id, id);
ALTER TABLE performance_verification_audits ADD CONSTRAINT fk_performance_verification_audit_performance FOREIGN KEY (performance_id)
        REFERENCES verified_performances (id);
ALTER TABLE performance_verification_audits ADD CONSTRAINT fk_performance_verification_audit_actor FOREIGN KEY (tenant_id, actor_membership_id)
        REFERENCES memberships (tenant_id, id);
ALTER TABLE purchase_limit_policies ADD CONSTRAINT fk_purchase_limit_policy_performance FOREIGN KEY (tenant_id,event_id,performance_id) REFERENCES verified_performances (tenant_id,event_id,id);
ALTER TABLE purchase_limit_policies ADD CONSTRAINT fk_purchase_limit_policy_actor FOREIGN KEY (tenant_id,changed_by_membership_id) REFERENCES memberships (tenant_id,id);
ALTER TABLE purchase_limit_policy_audits ADD CONSTRAINT fk_purchase_limit_policy_audit_policy FOREIGN KEY(policy_id) REFERENCES purchase_limit_policies(id);
ALTER TABLE report_versions ADD CONSTRAINT fk_report_versions_case FOREIGN KEY (tenant_id, event_id, case_id)
        REFERENCES cases (tenant_id, event_id, id);
ALTER TABLE report_versions ADD CONSTRAINT fk_report_versions_snapshot FOREIGN KEY (tenant_id, event_id, case_id, evidence_snapshot_id)
        REFERENCES evidence_snapshots (tenant_id, event_id, case_id, id);
ALTER TABLE report_versions ADD CONSTRAINT fk_report_versions_actor FOREIGN KEY (tenant_id, created_by_membership_id)
        REFERENCES memberships (tenant_id, id);
ALTER TABLE reservation_candidates ADD CONSTRAINT fk_reservation_candidate_batch FOREIGN KEY (batch_id) REFERENCES reservation_import_batches (id);
ALTER TABLE reservation_data_sources ADD CONSTRAINT fk_reservation_data_source_performance FOREIGN KEY (tenant_id, event_id, performance_id)
        REFERENCES verified_performances (tenant_id, event_id, id);
ALTER TABLE reservation_data_sources ADD CONSTRAINT fk_reservation_data_source_batch FOREIGN KEY (current_batch_id) REFERENCES reservation_import_batches (id);
ALTER TABLE reservation_import_batches ADD CONSTRAINT fk_reservation_import_batch_performance FOREIGN KEY (tenant_id, event_id, performance_id)
        REFERENCES verified_performances (tenant_id, event_id, id);
ALTER TABLE reservation_import_batches ADD CONSTRAINT fk_reservation_import_batch_actor FOREIGN KEY (tenant_id, created_by_membership_id)
        REFERENCES memberships (tenant_id, id);
ALTER TABLE reservation_import_errors ADD CONSTRAINT fk_reservation_import_error_batch FOREIGN KEY (batch_id) REFERENCES reservation_import_batches (id);
ALTER TABLE review_receipts ADD CONSTRAINT fk_review_receipt_request FOREIGN KEY
        (tenant_id, event_id, case_id, approval_request_id)
        REFERENCES approval_requests (tenant_id, event_id, case_id, id);
ALTER TABLE review_receipts ADD CONSTRAINT fk_review_receipt_report FOREIGN KEY
        (tenant_id, event_id, case_id, report_version_id)
        REFERENCES report_versions (tenant_id, event_id, case_id, id);
ALTER TABLE review_receipts ADD CONSTRAINT fk_review_receipt_snapshot FOREIGN KEY
        (tenant_id, event_id, case_id, evidence_snapshot_id)
        REFERENCES evidence_snapshots (tenant_id, event_id, case_id, id);
ALTER TABLE review_receipts ADD CONSTRAINT fk_review_receipt_approver FOREIGN KEY (tenant_id, approver_membership_id)
        REFERENCES memberships (tenant_id, id);
ALTER TABLE service_tenant_bindings ADD CONSTRAINT fk_service_tenant_bindings_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id);
ALTER TABLE synthetic_reservation_import_tenants ADD CONSTRAINT fk_synthetic_reservation_import_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id);
ALTER TABLE td_bulk_import_errors ADD CONSTRAINT fk_td_bulk_import_error_job FOREIGN KEY (job_id) REFERENCES td_bulk_import_jobs (id);
ALTER TABLE td_bulk_import_jobs ADD CONSTRAINT fk_td_bulk_import_actor FOREIGN KEY (tenant_id, created_by_membership_id)
        REFERENCES memberships (tenant_id, id);
ALTER TABLE td_bulk_import_scopes ADD CONSTRAINT fk_td_bulk_import_scope_job FOREIGN KEY (job_id, tenant_id)
        REFERENCES td_bulk_import_jobs (id, tenant_id);
ALTER TABLE td_bulk_import_scopes ADD CONSTRAINT fk_td_bulk_import_scope_performance FOREIGN KEY (tenant_id, event_id, performance_id)
        REFERENCES verified_performances (tenant_id, event_id, id);
ALTER TABLE td_bulk_import_staging_rows ADD CONSTRAINT fk_td_bulk_staging_job FOREIGN KEY (import_id, tenant_id) REFERENCES td_bulk_import_jobs (id, tenant_id);
ALTER TABLE td_bulk_import_staging_rows ADD CONSTRAINT fk_td_bulk_staging_performance FOREIGN KEY (tenant_id, event_id, performance_id) REFERENCES verified_performances (tenant_id, event_id, id);
ALTER TABLE td_bulk_import_upload_deletions ADD CONSTRAINT fk_td_bulk_upload_deletion_job FOREIGN KEY (import_id, tenant_id) REFERENCES td_bulk_import_jobs (id, tenant_id);
ALTER TABLE td_bulk_ledger_candidate_imports ADD CONSTRAINT fk_td_bulk_candidate_import_job FOREIGN KEY (import_id, tenant_id) REFERENCES td_bulk_import_jobs (id, tenant_id);
ALTER TABLE td_bulk_ledger_change_events ADD CONSTRAINT fk_td_bulk_ledger_change_version FOREIGN KEY (version_id) REFERENCES td_bulk_ledger_versions (id);
ALTER TABLE td_bulk_ledger_publication_heads ADD CONSTRAINT fk_td_bulk_ledger_publication_performance FOREIGN KEY (tenant_id, event_id, performance_id) REFERENCES verified_performances (tenant_id, event_id, id);
ALTER TABLE td_bulk_ledger_publication_heads ADD CONSTRAINT fk_td_bulk_ledger_publication_version FOREIGN KEY (version_id) REFERENCES td_bulk_ledger_versions (id);
ALTER TABLE td_bulk_ledger_publication_history ADD CONSTRAINT fk_td_bulk_ledger_history_publication FOREIGN KEY (publication_id) REFERENCES td_bulk_ledger_publications (id);
ALTER TABLE td_bulk_ledger_publication_history ADD CONSTRAINT fk_td_bulk_ledger_history_previous FOREIGN KEY (previous_version_id) REFERENCES td_bulk_ledger_versions (id);
ALTER TABLE td_bulk_ledger_publication_history ADD CONSTRAINT fk_td_bulk_ledger_history_version FOREIGN KEY (version_id) REFERENCES td_bulk_ledger_versions (id);
ALTER TABLE td_bulk_ledger_publication_history ADD CONSTRAINT fk_td_bulk_ledger_history_performance FOREIGN KEY (tenant_id,event_id,performance_id) REFERENCES verified_performances (tenant_id,event_id,id);
ALTER TABLE td_bulk_ledger_publication_outbox ADD CONSTRAINT fk_td_bulk_ledger_outbox_publication FOREIGN KEY (publication_id) REFERENCES td_bulk_ledger_publications (id);
ALTER TABLE td_bulk_ledger_publication_outbox ADD CONSTRAINT fk_td_bulk_ledger_outbox_history FOREIGN KEY (history_id) REFERENCES td_bulk_ledger_publication_history (id);
ALTER TABLE td_bulk_ledger_publication_outbox ADD CONSTRAINT fk_td_bulk_ledger_outbox_version FOREIGN KEY (version_id) REFERENCES td_bulk_ledger_versions (id);
ALTER TABLE td_bulk_ledger_publications ADD CONSTRAINT fk_td_bulk_ledger_publication_job FOREIGN KEY (import_id,tenant_id) REFERENCES td_bulk_import_jobs (id,tenant_id);
ALTER TABLE td_bulk_ledger_publications ADD CONSTRAINT fk_td_bulk_ledger_publication_actor FOREIGN KEY (tenant_id,requester_membership_id) REFERENCES memberships (tenant_id,id);
ALTER TABLE td_bulk_ledger_ticket_versions ADD CONSTRAINT fk_td_bulk_ledger_ticket_version FOREIGN KEY (version_id) REFERENCES td_bulk_ledger_versions (id);
ALTER TABLE td_bulk_ledger_track1_analysis_jobs ADD CONSTRAINT fk_td_bl06_analysis_outbox FOREIGN KEY (outbox_id) REFERENCES td_bulk_ledger_publication_outbox (id);
ALTER TABLE td_bulk_ledger_track1_analysis_jobs ADD CONSTRAINT fk_td_bl06_analysis_version FOREIGN KEY (version_id) REFERENCES td_bulk_ledger_versions (id);
ALTER TABLE td_bulk_ledger_track1_bulk_clusters ADD CONSTRAINT fk_bl07_cluster_job FOREIGN KEY(job_id) REFERENCES td_bulk_ledger_track1_bulk_jobs(id);
ALTER TABLE td_bulk_ledger_track1_bulk_jobs ADD CONSTRAINT fk_bl07_outbox FOREIGN KEY (outbox_id) REFERENCES td_bulk_ledger_publication_outbox(id);
ALTER TABLE td_bulk_ledger_track1_bulk_limit_evaluations ADD CONSTRAINT fk_bl07_limit_evaluation_job FOREIGN KEY(job_id) REFERENCES td_bulk_ledger_track1_bulk_jobs(id);
ALTER TABLE td_bulk_ledger_track1_bulk_limit_finding_reservations ADD CONSTRAINT fk_bl07_limit_finding_reservation FOREIGN KEY(job_id,performance_id,account_reference_hmac)
        REFERENCES td_bulk_ledger_track1_bulk_limit_findings(job_id,performance_id,account_reference_hmac);
ALTER TABLE td_bulk_ledger_track1_bulk_limit_findings ADD CONSTRAINT fk_bl07_limit_finding_evaluation FOREIGN KEY(job_id,performance_id)
        REFERENCES td_bulk_ledger_track1_bulk_limit_evaluations(job_id,performance_id);
ALTER TABLE td_bulk_ledger_track1_bulk_members ADD CONSTRAINT fk_bl07_member_cluster FOREIGN KEY(job_id,cluster_id) REFERENCES td_bulk_ledger_track1_bulk_clusters(job_id,cluster_id);
ALTER TABLE td_bulk_ledger_track1_bulk_shared_signal_observations ADD CONSTRAINT fk_bl07_shared_signal_job FOREIGN KEY(job_id) REFERENCES td_bulk_ledger_track1_bulk_jobs(id);
ALTER TABLE td_bulk_ledger_track1_policy_snapshots ADD CONSTRAINT fk_bl07_policy_snapshot_job FOREIGN KEY (job_id) REFERENCES td_bulk_ledger_track1_bulk_jobs(id);
ALTER TABLE td_bulk_ledger_track1_ticket_signals ADD CONSTRAINT fk_td_bl06_signal_version FOREIGN KEY (version_id) REFERENCES td_bulk_ledger_versions (id);
ALTER TABLE td_bulk_ledger_versions ADD CONSTRAINT fk_td_bulk_ledger_version_candidate FOREIGN KEY (candidate_import_id) REFERENCES td_bulk_ledger_candidate_imports (id);
ALTER TABLE td_bulk_ledger_versions ADD CONSTRAINT fk_td_bulk_ledger_version_import FOREIGN KEY (import_id, tenant_id) REFERENCES td_bulk_import_jobs (id, tenant_id);
ALTER TABLE td_bulk_ledger_versions ADD CONSTRAINT fk_td_bulk_ledger_version_performance FOREIGN KEY (tenant_id, event_id, performance_id) REFERENCES verified_performances (tenant_id, event_id, id);
ALTER TABLE td_bulk_ledger_versions ADD CONSTRAINT fk_td_bulk_ledger_version_base FOREIGN KEY (base_version_id) REFERENCES td_bulk_ledger_versions (id);
ALTER TABLE td_bulk_ledger_versions ADD CONSTRAINT fk_td_bulk_ledger_version_expected_head FOREIGN KEY (expected_head_version_id) REFERENCES td_bulk_ledger_versions (id);
ALTER TABLE td_multi_event_investigation_candidates ADD CONSTRAINT fk_mei_candidate_run FOREIGN KEY (run_id) REFERENCES td_multi_event_investigation_runs (id);
ALTER TABLE td_multi_event_investigation_members ADD CONSTRAINT fk_mei_member_candidate FOREIGN KEY (run_id,candidate_id)
        REFERENCES td_multi_event_investigation_candidates (run_id,candidate_id);
ALTER TABLE td_multi_event_investigation_relationship_members ADD CONSTRAINT fk_mei_relationship_member_relationship FOREIGN KEY (relationship_id)
        REFERENCES td_multi_event_investigation_relationships (id);
ALTER TABLE td_multi_event_investigation_relationship_members ADD CONSTRAINT fk_mei_relationship_member_candidate FOREIGN KEY (run_id,candidate_id,account_reference_hmac)
        REFERENCES td_multi_event_investigation_members (run_id,candidate_id,account_reference_hmac);
ALTER TABLE td_multi_event_investigation_relationship_sources ADD CONSTRAINT fk_mei_relationship_source_relationship FOREIGN KEY (relationship_id)
        REFERENCES td_multi_event_investigation_relationships (id);
ALTER TABLE td_multi_event_investigation_relationship_sources ADD CONSTRAINT fk_mei_relationship_source_scope FOREIGN KEY (run_id,performance_id)
        REFERENCES td_multi_event_investigation_scopes (run_id,performance_id);
ALTER TABLE td_multi_event_investigation_relationships ADD CONSTRAINT fk_mei_relationship_candidate FOREIGN KEY (run_id,candidate_id)
        REFERENCES td_multi_event_investigation_candidates (run_id,candidate_id);
ALTER TABLE td_multi_event_investigation_runs ADD CONSTRAINT fk_mei_requester FOREIGN KEY (tenant_id,requester_membership_id) REFERENCES memberships (tenant_id,id);
ALTER TABLE td_multi_event_investigation_scopes ADD CONSTRAINT fk_mei_scope_run FOREIGN KEY (run_id) REFERENCES td_multi_event_investigation_runs (id);
ALTER TABLE td_multi_event_investigation_scopes ADD CONSTRAINT fk_mei_scope_performance FOREIGN KEY (tenant_id,event_id,performance_id) REFERENCES verified_performances (tenant_id,event_id,id);
ALTER TABLE td_multi_event_investigation_scopes ADD CONSTRAINT fk_mei_scope_version_identity
        FOREIGN KEY (ledger_version_id, tenant_id, event_id, performance_id)
        REFERENCES td_bulk_ledger_versions (id, tenant_id, event_id, performance_id);
ALTER TABLE td_multi_event_investigation_scopes ADD CONSTRAINT fk_mei_scope_policy_snapshot_version
        FOREIGN KEY (policy_snapshot_id, ledger_version_id)
        REFERENCES td_bulk_ledger_track1_policy_snapshots (snapshot_id, ledger_version_id);
ALTER TABLE track1_purchase_ledger_batches ADD CONSTRAINT fk_t1_ledger_batch_performance FOREIGN KEY (tenant_id, event_id, performance_id)
        REFERENCES verified_performances (tenant_id, event_id, id);
ALTER TABLE track1_purchase_ledger_batches ADD CONSTRAINT fk_t1_ledger_batch_actor FOREIGN KEY (tenant_id, created_by_membership_id)
        REFERENCES memberships (tenant_id, id);
ALTER TABLE track1_purchase_ledger_errors ADD CONSTRAINT fk_t1_ledger_error_batch FOREIGN KEY (batch_id) REFERENCES track1_purchase_ledger_batches (id);
ALTER TABLE track1_purchase_ledger_rows ADD CONSTRAINT fk_t1_ledger_row_batch FOREIGN KEY (batch_id) REFERENCES track1_purchase_ledger_batches (id);
ALTER TABLE track1_purchase_limit_finding_reservations ADD CONSTRAINT fk_t1_limit_finding_reservation FOREIGN KEY (finding_id) REFERENCES track1_purchase_limit_findings (id);
ALTER TABLE track1_purchase_limit_findings ADD CONSTRAINT fk_t1_limit_finding_run FOREIGN KEY (run_id) REFERENCES track1_purchase_limit_runs (id);
ALTER TABLE track1_purchase_limit_run_clusters ADD CONSTRAINT fk_t1_limit_run_cluster FOREIGN KEY (run_id) REFERENCES track1_purchase_limit_runs (id);
ALTER TABLE track1_purchase_limit_runs ADD CONSTRAINT fk_t1_limit_run_performance FOREIGN KEY (tenant_id, event_id, performance_id)
        REFERENCES verified_performances (tenant_id, event_id, id);
ALTER TABLE track1_purchase_limit_runs ADD CONSTRAINT fk_t1_limit_run_actor FOREIGN KEY (tenant_id, created_by_membership_id)
        REFERENCES memberships (tenant_id, id);
ALTER TABLE track1_purchase_limit_runs ADD CONSTRAINT fk_t1_limit_run_policy FOREIGN KEY (policy_id) REFERENCES purchase_limit_policies (id);
ALTER TABLE track1_purchase_limit_runs ADD CONSTRAINT fk_t1_limit_run_batch FOREIGN KEY (ledger_batch_id) REFERENCES track1_purchase_ledger_batches (id);
ALTER TABLE track1_purchase_limit_runs ADD CONSTRAINT fk_t1_limit_run_version FOREIGN KEY (ledger_version_id) REFERENCES td_bulk_ledger_versions (id);
ALTER TABLE track1_purchase_limit_runs ADD CONSTRAINT fk_t1_limit_run_outbox FOREIGN KEY (publication_outbox_id) REFERENCES td_bulk_ledger_publication_outbox (id);
ALTER TABLE track1_purchase_limit_shared_signal_observations ADD CONSTRAINT fk_t1_signal_observation_run FOREIGN KEY (run_id) REFERENCES track1_purchase_limit_runs (id);
ALTER TABLE verified_performances ADD CONSTRAINT fk_verified_performance_event FOREIGN KEY (tenant_id, event_id) REFERENCES events (tenant_id, id);
ALTER TABLE verified_performances ADD CONSTRAINT fk_verified_performance_actor FOREIGN KEY (tenant_id, verified_by_membership_id)
        REFERENCES memberships (tenant_id, id);
ALTER TABLE verified_performances ADD CONSTRAINT fk_verified_performance_withdrawn_actor FOREIGN KEY (tenant_id, withdrawn_by_membership_id)
        REFERENCES memberships (tenant_id, id);

ALTER TABLE approval_audit_events ADD CONSTRAINT chk_approval_audit_event CHECK (event_type IN
        ('APPROVAL_REQUESTED', 'REVIEW_RECEIPT_ISSUED', 'APPROVAL_DECIDED',
         'REVIEW_RECEIPT_REVOKED', 'REVIEW_RECEIPT_EXPIRED', 'APPROVAL_WITHDRAWN',
         'APPROVAL_INVALIDATED'));
ALTER TABLE approval_audit_events ADD CONSTRAINT chk_approval_audit_versions CHECK
        (evidence_revision >= 1 AND case_version_before >= 0
            AND case_version_after >= case_version_before);
ALTER TABLE approval_decisions ADD CONSTRAINT chk_approval_decision_outcome CHECK (outcome IN ('APPROVE', 'REJECT'));
ALTER TABLE approval_decisions ADD CONSTRAINT chk_approval_decision_versions CHECK
        (report_revision >= 1 AND evidence_revision >= 1 AND case_version_before >= 0
            AND case_version_after > case_version_before
            AND request_version_at_decision >= 0 AND report_version_at_decision >= 0);
ALTER TABLE approval_requests ADD CONSTRAINT chk_approval_request_status CHECK (status IN
        ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'INVALIDATED'));
ALTER TABLE approval_requests ADD CONSTRAINT chk_approval_request_versions CHECK
        (report_revision >= 1 AND evidence_revision >= 1
            AND case_version_at_submit >= 0 AND request_version >= 0);
ALTER TABLE approval_requests ADD CONSTRAINT chk_approval_request_active CHECK
        ((status = 'PENDING' AND active_case_id IS NOT NULL AND active_case_id = case_id)
            OR (status <> 'PENDING' AND active_case_id IS NULL));
ALTER TABLE case_activities ADD CONSTRAINT chk_case_activities_versions CHECK (version_after >= 0 AND evidence_revision >= 1);
ALTER TABLE case_activities ADD CONSTRAINT chk_case_activities_actor_role CHECK
        (actor_role IN ('ANALYST', 'REVIEWER', 'APPROVER', 'SYSTEM'));
ALTER TABLE case_source_references ADD CONSTRAINT chk_case_source_shape CHECK (
    (source_type = 'STORED_CLUSTER_EVIDENCE' AND analysis_request_id IS NOT NULL
        AND cluster_id IS NOT NULL AND anchor_audit_id IS NOT NULL
        AND fan_report_id IS NULL AND evidence_id IS NULL AND listing_candidate_match_run_id IS NULL)
    OR (source_type = 'FAN_REPORT' AND fan_report_id IS NOT NULL
        AND evidence_id IS NULL AND anchor_audit_id IS NULL
        AND analysis_request_id IS NULL AND cluster_id IS NULL AND listing_candidate_match_run_id IS NULL)
    OR (source_type = 'EVIDENCE_ASSET' AND fan_report_id IS NOT NULL
        AND evidence_id IS NOT NULL AND anchor_audit_id IS NULL
        AND analysis_request_id IS NULL AND cluster_id IS NULL AND listing_candidate_match_run_id IS NULL)
    OR (source_type = 'LISTING_CANDIDATE_MATCH' AND fan_report_id IS NOT NULL AND evidence_id IS NOT NULL
        AND listing_candidate_match_run_id IS NOT NULL AND candidate_analysis_id IS NOT NULL
        AND candidate_analysis_row_version IS NOT NULL AND candidate_analysis_response_sha256 IS NOT NULL
        AND candidate_evidence_sha256 IS NOT NULL AND candidate_performance_link_id IS NOT NULL
        AND candidate_performance_link_revision IS NOT NULL AND candidate_verified_performance_id IS NOT NULL
        AND candidate_verified_performance_revision IS NOT NULL AND candidate_reservation_source_id IS NOT NULL
        AND candidate_reservation_source_revision IS NOT NULL AND candidate_reservation_batch_id IS NOT NULL
        AND candidate_reservation_source_version IS NOT NULL AND candidate_status IS NOT NULL
        AND candidate_reason IS NOT NULL AND (candidate_count IS NULL OR candidate_count >= 0)
        AND anchor_audit_id IS NULL AND analysis_request_id IS NULL AND cluster_id IS NULL)
);
ALTER TABLE cases ADD CONSTRAINT chk_cases_revision CHECK (evidence_revision >= 1 AND case_version >= 0);
ALTER TABLE cases ADD CONSTRAINT chk_cases_relation CHECK ((related_case_id IS NULL AND relation_reason IS NULL)
        OR (related_case_id IS NOT NULL AND relation_reason IS NOT NULL));
ALTER TABLE events ADD CONSTRAINT chk_events_status CHECK (status IN ('ACTIVE', 'INACTIVE'));
ALTER TABLE evidence_access_audit_events ADD CONSTRAINT chk_evidence_access_audit_outcome CHECK (outcome IN ('SUCCEEDED', 'FAILED'));
ALTER TABLE evidence_access_audit_events ADD CONSTRAINT chk_evidence_access_audit_result CHECK (
        (outcome = 'SUCCEEDED' AND detail_code = 'VERIFIED_FOR_RESPONSE' AND evidence_id IS NOT NULL)
        OR (outcome = 'FAILED' AND detail_code <> 'VERIFIED_FOR_RESPONSE'));
ALTER TABLE evidence_access_audit_events ADD CONSTRAINT chk_evidence_access_audit_code CHECK (detail_code IN ('VERIFIED_FOR_RESPONSE',
        'RESOURCE_NOT_FOUND', 'ACCESS_DENIED', 'TENANT_SELECTION_REQUIRED', 'SOURCE_NOT_AVAILABLE',
        'RETENTION_EXPIRED', 'UNSUPPORTED_IMAGE_TYPE', 'EVIDENCE_CONTENT_TOO_LARGE', 'EVIDENCE_CONTENT_BUSY',
        'OBJECT_CHANGED', 'OBJECT_MISSING', 'STORAGE_UNAVAILABLE', 'SIZE_MISMATCH', 'MEDIA_TYPE_MISMATCH',
        'HASH_MISMATCH', 'SOURCE_CHANGED', 'AUDIT_UNAVAILABLE', 'INTERNAL_ERROR'));
ALTER TABLE evidence_assets ADD CONSTRAINT chk_evidence_assets_size CHECK (byte_size > 0);
ALTER TABLE evidence_preservation_attempts ADD CONSTRAINT chk_preservation_attempt_outcome CHECK (outcome IN
        ('VERIFIED', 'POLICY_REJECTED', 'STORAGE_REJECTED', 'STORAGE_UNAVAILABLE', 'CONFLICT'));
ALTER TABLE evidence_preservation_attempts ADD CONSTRAINT chk_preservation_attempt_shape CHECK (
        (outcome = 'VERIFIED' AND preservation_record_id IS NOT NULL
            AND reason_code = 'IMMUTABLE_VERIFIED')
        OR (outcome <> 'VERIFIED' AND preservation_record_id IS NULL));
ALTER TABLE evidence_preservation_policies ADD CONSTRAINT chk_preservation_policy_version CHECK (policy_version >= 1);
ALTER TABLE evidence_preservation_policies ADD CONSTRAINT chk_preservation_policy_status CHECK (policy_status IN
        ('DRAFT', 'APPROVED', 'REVOKED', 'EXPIRED'));
ALTER TABLE evidence_preservation_policies ADD CONSTRAINT chk_preservation_policy_period CHECK (effective_until > effective_from);
ALTER TABLE evidence_preservation_policies ADD CONSTRAINT chk_preservation_policy_limit CHECK
        (retention_limit_days BETWEEN 1 AND 3650);
ALTER TABLE evidence_preservation_policies ADD CONSTRAINT chk_preservation_policy_mode CHECK (preservation_mode IN
        ('GOVERNANCE', 'COMPLIANCE'));
ALTER TABLE evidence_preservation_policies ADD CONSTRAINT chk_preservation_policy_approval CHECK (
        (policy_status = 'DRAFT' AND approval_authority IS NULL
            AND approved_at IS NULL AND revoked_at IS NULL)
        OR (policy_status IN ('APPROVED', 'EXPIRED') AND approval_authority IS NOT NULL
            AND approval_authority <> '' AND approved_at IS NOT NULL AND revoked_at IS NULL)
        OR (policy_status = 'REVOKED' AND approval_authority IS NOT NULL
            AND approval_authority <> '' AND approved_at IS NOT NULL AND revoked_at IS NOT NULL));
ALTER TABLE evidence_preservation_records ADD CONSTRAINT chk_preservation_record_status CHECK
        (preservation_status = 'IMMUTABLE_VERIFIED');
ALTER TABLE evidence_preservation_records ADD CONSTRAINT chk_preservation_record_mode CHECK
        (object_lock_mode IN ('GOVERNANCE', 'COMPLIANCE'));
ALTER TABLE evidence_preservation_records ADD CONSTRAINT chk_preservation_record_size CHECK (object_size > 0);
ALTER TABLE evidence_preservation_records ADD CONSTRAINT chk_preservation_record_retention CHECK (retain_until > verified_at);
ALTER TABLE evidence_snapshots ADD CONSTRAINT chk_evidence_snapshots_revision CHECK (evidence_revision >= 1 AND case_version_at_capture >= 0);
ALTER TABLE evidence_snapshots ADD CONSTRAINT chk_evidence_snapshots_integrity CHECK (source_integrity IN ('NO_FILE', 'METADATA_ONLY'));
ALTER TABLE evidence_snapshots ADD CONSTRAINT chk_evidence_snapshots_schema
    CHECK (manifest_schema_version IN (1, 2));
ALTER TABLE evidence_verification_records ADD CONSTRAINT chk_evidence_verifications_status CHECK (verification_status IN
        ('MATCH', 'HASH_MISMATCH', 'SIZE_MISMATCH', 'OBJECT_MISSING', 'OBJECT_CHANGED',
         'STORAGE_UNAVAILABLE', 'TOO_LARGE', 'RETENTION_EXPIRED'));
ALTER TABLE evidence_verification_records ADD CONSTRAINT chk_evidence_verifications_preservation CHECK (preservation_status IN
        ('UNPROTECTED', 'VERSIONED_MUTABLE', 'UNKNOWN'));
ALTER TABLE evidence_verification_records ADD CONSTRAINT chk_evidence_verifications_marker CHECK (marker_kind IN ('VERSION_ID', 'ETAG', 'NONE'));
ALTER TABLE evidence_verification_records ADD CONSTRAINT chk_evidence_verifications_method CHECK (verification_method IN ('STREAMED_SHA256', 'NOT_READ'));
ALTER TABLE evidence_verification_records ADD CONSTRAINT chk_evidence_verifications_size CHECK (expected_byte_size > 0
        AND (verified_byte_size IS NULL OR verified_byte_size >= 0));
ALTER TABLE fan_report_performance_links ADD CONSTRAINT chk_fan_report_performance_link_status CHECK (
        (status = 'ACTIVE' AND active_fan_report_id = fan_report_id AND ended_by_membership_id IS NULL AND ended_at IS NULL)
        OR (status IN ('REPLACED', 'WITHDRAWN') AND active_fan_report_id IS NULL
            AND ended_by_membership_id IS NOT NULL AND ended_at IS NOT NULL));
ALTER TABLE fan_report_performance_links ADD CONSTRAINT chk_fan_report_performance_link_values CHECK (
        link_revision >= 1 AND performance_revision >= 1 AND CHAR_LENGTH(TRIM(reason)) > 0);
ALTER TABLE fan_reports ADD CONSTRAINT chk_fan_reports_asking_price CHECK (asking_price IS NULL OR asking_price > 0);
ALTER TABLE issuance_holds ADD CONSTRAINT chk_issuance_hold_status CHECK (status IN ('ACTIVE', 'RELEASED'));
ALTER TABLE issuance_holds ADD CONSTRAINT chk_issuance_hold_shape CHECK
        ((status = 'ACTIVE' AND released_at IS NULL)
            OR (status = 'RELEASED' AND released_at IS NOT NULL));
ALTER TABLE issuance_holds ADD CONSTRAINT chk_issuance_hold_revision CHECK
        (evidence_revision >= 1 AND hold_version >= 0);
ALTER TABLE listing_candidate_match_runs ADD CONSTRAINT chk_listing_candidate_run_status CHECK (status IN
        ('IN_PROGRESS', 'MATCH_CANDIDATE', 'NO_MATCH', 'AMBIGUOUS', 'UNVERIFIABLE', 'LOOKUP_FAILED', 'ANALYSIS_FAILED'));
ALTER TABLE listing_candidate_match_runs ADD CONSTRAINT chk_listing_candidate_run_lifecycle CHECK (
        (status = 'IN_PROGRESS' AND active_analysis_id = analysis_id AND reason = 'LOOKUP_STARTED'
            AND completed_at IS NULL AND lease_expires_at IS NOT NULL)
        OR (status <> 'IN_PROGRESS' AND active_analysis_id IS NULL AND completed_at IS NOT NULL
            AND lease_expires_at IS NULL));
ALTER TABLE listing_candidate_match_runs ADD CONSTRAINT chk_listing_candidate_run_candidate CHECK (
        (status = 'MATCH_CANDIDATE' AND candidate_count = 1 AND candidate_id IS NOT NULL)
        OR (status = 'NO_MATCH' AND candidate_count = 0 AND candidate_id IS NULL)
        OR (status = 'AMBIGUOUS' AND candidate_count >= 2 AND candidate_id IS NULL)
        OR (status IN ('UNVERIFIABLE', 'LOOKUP_FAILED', 'ANALYSIS_FAILED', 'IN_PROGRESS') AND candidate_id IS NULL));
ALTER TABLE listing_candidate_match_runs ADD CONSTRAINT chk_listing_candidate_run_digests CHECK (
        idempotency_key_digest REGEXP '^[0-9a-f]{64}$' AND request_digest REGEXP '^[0-9a-f]{64}$'
        AND (evidence_sha256 IS NULL OR evidence_sha256 REGEXP '^[0-9a-f]{64}$')
        AND (analysis_response_sha256 IS NULL OR analysis_response_sha256 REGEXP '^[0-9a-f]{64}$'));
ALTER TABLE listing_context_analyses ADD CONSTRAINT chk_listing_analyses_execution CHECK (execution_status IN
        ('IN_PROGRESS', 'SUCCEEDED', 'INPUT_REJECTED', 'STORAGE_FAILED', 'ANALYZER_FAILED', 'ABANDONED'));
ALTER TABLE listing_context_analyses ADD CONSTRAINT chk_listing_analyses_status CHECK (analysis_status IS NULL OR analysis_status IN
        ('COMPLETED', 'PARTIAL', 'UNVERIFIABLE', 'FAILED'));
ALTER TABLE listing_context_analyses ADD CONSTRAINT chk_listing_analyses_active CHECK (
        (execution_status = 'IN_PROGRESS' AND active_evidence_id IS NOT NULL AND active_evidence_id = evidence_id AND analysis_status IS NULL
            AND completed_at IS NULL)
        OR (execution_status <> 'IN_PROGRESS' AND active_evidence_id IS NULL AND completed_at IS NOT NULL));
ALTER TABLE listing_context_analyses ADD CONSTRAINT chk_listing_analyses_outcome CHECK (
        execution_status = 'IN_PROGRESS'
        OR (execution_status = 'SUCCEEDED' AND analysis_status IS NOT NULL
            AND analysis_status IN ('COMPLETED', 'PARTIAL', 'UNVERIFIABLE')
            AND error_code IS NULL AND model_name IS NOT NULL AND analyzed_at IS NOT NULL
            AND review_required IS NOT NULL AND review_required = b'1' AND response_sha256 IS NOT NULL
            AND verified_sha256 IS NOT NULL AND verified_sha256 = expected_sha256
            AND verified_byte_size IS NOT NULL AND verified_byte_size = expected_byte_size)
        OR (execution_status NOT IN ('IN_PROGRESS', 'SUCCEEDED') AND analysis_status IS NOT NULL
            AND analysis_status = 'FAILED'
            AND error_code IS NOT NULL AND model_name IS NULL AND analyzed_at IS NULL));
ALTER TABLE listing_context_analyses ADD CONSTRAINT chk_listing_analyses_candidate CHECK (
        (execution_status = 'IN_PROGRESS' AND candidate_match_status IS NULL AND candidate_match_reason IS NULL)
        OR (execution_status <> 'IN_PROGRESS' AND candidate_match_status IS NOT NULL
            AND candidate_match_status = 'NOT_LINKED' AND candidate_match_reason IS NOT NULL
            AND candidate_match_reason IN ('PERFORMANCE_REF_UNVERIFIED', 'SEAT_NOT_FOUND',
                'MATCH_SCORE_UNAVAILABLE', 'ANALYSIS_NOT_SUCCEEDED')));
ALTER TABLE listing_context_analyses ADD CONSTRAINT chk_listing_analyses_size CHECK (expected_byte_size > 0
        AND (verified_byte_size IS NULL OR verified_byte_size >= 0));
ALTER TABLE listing_context_observations ADD CONSTRAINT chk_listing_observations_field CHECK (field_name IN ('eventName', 'performanceAt',
        'platformDisplayName', 'askingPrice', 'currency', 'saleText', 'zone', 'row', 'seat'));
ALTER TABLE listing_context_observations ADD CONSTRAINT chk_listing_observations_state CHECK (
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
            AND candidate_2 IS NOT NULL));
ALTER TABLE listing_context_observations ADD CONSTRAINT chk_listing_observations_candidates CHECK (
        (candidate_2 IS NULL OR candidate_1 IS NOT NULL) AND (candidate_3 IS NULL OR candidate_2 IS NOT NULL));
ALTER TABLE macro_audit_evidence ADD CONSTRAINT chk_macro_audit_evidence_scope
        CHECK ((tenant_id IS NULL AND internal_event_id IS NULL)
            OR (tenant_id IS NOT NULL AND internal_event_id IS NOT NULL));
ALTER TABLE membership_roles ADD CONSTRAINT chk_membership_roles_role CHECK (role IN ('ANALYST', 'REVIEWER', 'APPROVER', 'TENANT_ADMIN'));
ALTER TABLE memberships ADD CONSTRAINT chk_memberships_status CHECK (status IN ('ACTIVE', 'INACTIVE'));
ALTER TABLE official_pdf_artifacts ADD CONSTRAINT chk_official_pdf_artifact_values CHECK
        (report_revision >= 1 AND evidence_revision >= 1 AND pdf_size > 0);
ALTER TABLE official_pdf_audit_events ADD CONSTRAINT chk_official_pdf_audit_event CHECK (event_type IN
        ('ISSUANCE_RESERVED', 'ISSUANCE_RETRIED', 'OBJECT_STORED', 'ISSUANCE_GENERATED',
         'ISSUANCE_FAILED', 'ISSUANCE_STALE', 'ARTIFACT_DOWNLOADED'));
ALTER TABLE official_pdf_issuance_attempts ADD CONSTRAINT chk_official_pdf_attempt_kind CHECK (attempt_kind IN ('REQUEST', 'RETRY', 'RECOVERY'));
ALTER TABLE official_pdf_issuance_attempts ADD CONSTRAINT chk_official_pdf_attempt_status CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'STALE'));
ALTER TABLE official_pdf_issuance_attempts ADD CONSTRAINT chk_official_pdf_attempt_shape CHECK
        ((status = 'RUNNING' AND finished_at IS NULL AND failure_code IS NULL)
         OR (status = 'SUCCEEDED' AND finished_at IS NOT NULL AND failure_code IS NULL)
         OR (status IN ('FAILED', 'STALE') AND finished_at IS NOT NULL AND failure_code IS NOT NULL));
ALTER TABLE official_pdf_issuance_attempts ADD CONSTRAINT chk_official_pdf_attempt_number CHECK (attempt_number >= 1);
ALTER TABLE official_pdf_issuances ADD CONSTRAINT chk_official_pdf_issuance_status CHECK (status IN
        ('RESERVED', 'RENDERING', 'OBJECT_STORED', 'GENERATED', 'FAILED', 'STALE'));
ALTER TABLE official_pdf_issuances ADD CONSTRAINT chk_official_pdf_issuance_values CHECK
        (report_revision >= 1 AND evidence_revision >= 1 AND attempt_count >= 0
            AND issuance_version >= 0);
ALTER TABLE official_pdf_issuances ADD CONSTRAINT chk_official_pdf_issuance_candidate CHECK
        ((status IN ('OBJECT_STORED', 'GENERATED')
            AND candidate_object_version IS NOT NULL AND candidate_pdf_sha256 IS NOT NULL
            AND candidate_pdf_size IS NOT NULL AND candidate_pdf_size > 0)
         OR (status NOT IN ('OBJECT_STORED', 'GENERATED')));
ALTER TABLE official_pdf_issuances ADD CONSTRAINT chk_official_pdf_issuance_completed CHECK
        ((status = 'GENERATED' AND completed_at IS NOT NULL)
         OR (status <> 'GENERATED' AND completed_at IS NULL));
ALTER TABLE official_pdf_synthetic_tenants ADD CONSTRAINT chk_official_pdf_synthetic_isolation
        CHECK (isolation_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$');
ALTER TABLE performance_link_audits ADD CONSTRAINT chk_performance_link_audit_action CHECK (action IN ('LINKED', 'REPLACED', 'WITHDRAWN'));
ALTER TABLE performance_link_audits ADD CONSTRAINT chk_performance_link_audit_digests CHECK (
        idempotency_key_digest REGEXP '^[0-9a-f]{64}$' AND request_digest REGEXP '^[0-9a-f]{64}$');
ALTER TABLE performance_verification_audits ADD CONSTRAINT chk_performance_verification_audit_action CHECK (action IN ('VERIFIED', 'REVISED', 'WITHDRAWN'));
ALTER TABLE performance_verification_audits ADD CONSTRAINT chk_performance_verification_audit_revision CHECK (revision >= 1);
ALTER TABLE purchase_limit_policies ADD CONSTRAINT chk_purchase_limit_policy_values CHECK (max_tickets_per_account >= 1 AND revision >= 1 AND status IN ('ACTIVE','WITHDRAWN') AND (effective_until IS NULL OR effective_until > effective_from));
ALTER TABLE purchase_limit_policy_audits ADD CONSTRAINT chk_purchase_limit_policy_audit_values CHECK (max_tickets_per_account >= 1 AND revision >= 1 AND action IN ('CREATED','REVISED','WITHDRAWN') AND status IN ('ACTIVE','WITHDRAWN') AND (effective_until IS NULL OR effective_until > effective_from));
ALTER TABLE report_versions ADD CONSTRAINT chk_report_versions_revision CHECK (evidence_revision >= 1 AND revision >= 1 AND version >= 0);
ALTER TABLE report_versions ADD CONSTRAINT chk_report_versions_status CHECK (status IN
        ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'INVALIDATED'));
ALTER TABLE reservation_candidates ADD CONSTRAINT chk_reservation_candidate_values CHECK (
        source_row_index >= 0 AND reservation_ref_hmac REGEXP '^[0-9a-f]{64}$'
        AND CHAR_LENGTH(TRIM(reference_hmac_key_version)) > 0 AND CHAR_LENGTH(TRIM(zone)) > 0
        AND CHAR_LENGTH(TRIM(row_num)) > 0 AND CHAR_LENGTH(TRIM(seat_num)) > 0);
ALTER TABLE reservation_data_sources ADD CONSTRAINT chk_reservation_data_source_complete CHECK (
        complete_snapshot = b'1' AND source_revision >= 1 AND CHAR_LENGTH(TRIM(current_source_version)) > 0);
ALTER TABLE reservation_import_batches ADD CONSTRAINT chk_reservation_import_batch_status CHECK (status IN ('ACCEPTED', 'REJECTED'));
ALTER TABLE reservation_import_batches ADD CONSTRAINT chk_reservation_import_batch_counts CHECK (
        input_row_count >= 0 AND accepted_row_count >= 0 AND rejected_row_count >= 0
        AND ((status = 'ACCEPTED' AND complete_snapshot = b'1' AND accepted_row_count = input_row_count
                AND rejected_row_count = 0)
            OR (status = 'REJECTED' AND accepted_row_count = 0 AND rejected_row_count > 0)));
ALTER TABLE reservation_import_batches ADD CONSTRAINT chk_reservation_import_batch_digests CHECK (
        idempotency_key_digest REGEXP '^[0-9a-f]{64}$' AND request_digest REGEXP '^[0-9a-f]{64}$'
        AND CHAR_LENGTH(TRIM(source_version)) > 0 AND CHAR_LENGTH(TRIM(reference_hmac_key_version)) > 0);
ALTER TABLE reservation_import_errors ADD CONSTRAINT chk_reservation_import_error_row CHECK (row_index >= 0);
ALTER TABLE review_receipts ADD CONSTRAINT chk_review_receipt_status CHECK (status IN
        ('ISSUED', 'CONSUMED', 'REVOKED', 'EXPIRED'));
ALTER TABLE review_receipts ADD CONSTRAINT chk_review_receipt_shape CHECK (
        (status = 'ISSUED' AND active_approval_request_id IS NOT NULL
            AND active_approval_request_id = approval_request_id
            AND consumed_at IS NULL AND revoked_at IS NULL)
        OR (status = 'CONSUMED' AND active_approval_request_id IS NULL
            AND consumed_at IS NOT NULL AND revoked_at IS NULL)
        OR (status = 'REVOKED' AND active_approval_request_id IS NULL
            AND consumed_at IS NULL AND revoked_at IS NOT NULL)
        OR (status = 'EXPIRED' AND active_approval_request_id IS NULL
            AND consumed_at IS NULL AND revoked_at IS NULL));
ALTER TABLE review_receipts ADD CONSTRAINT chk_review_receipt_values CHECK
        (report_revision >= 1 AND evidence_revision >= 1 AND receipt_version >= 0
            AND expires_at > issued_at);
ALTER TABLE service_tenant_bindings ADD CONSTRAINT chk_service_tenant_bindings_status CHECK (status IN ('ACTIVE', 'INACTIVE'));
ALTER TABLE synthetic_reservation_import_tenants ADD CONSTRAINT chk_synthetic_reservation_import_isolation CHECK (
        isolation_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$');
ALTER TABLE td_bulk_import_errors ADD CONSTRAINT chk_td_bulk_import_error_values CHECK (sequence_no >= 0);
ALTER TABLE td_bulk_import_jobs ADD CONSTRAINT chk_td_bulk_import_job_values CHECK (
    mode IN ('FULL_SNAPSHOT', 'CHANGE_FEED') AND format = 'CSV'
    AND status IN ('CREATED','UPLOAD_PENDING','UPLOADED','VALIDATING','STAGING','READY_TO_PUBLISH','PUBLISHED','FAILED_RETRYABLE','REJECTED','CANCELLED')
    AND declared_file_bytes >= 0 AND declared_row_count >= 0 AND processed_row_count >= 0 AND accepted_row_count >= 0 AND rejected_row_count >= 0
    AND processed_row_count = accepted_row_count + rejected_row_count
    AND ((mode = 'FULL_SNAPSHOT' AND complete_snapshot = b'1') OR (mode = 'CHANGE_FEED' AND complete_snapshot = b'0'))
    AND ((status = 'CREATED' AND upload_object_key_digest IS NULL AND upload_object_version IS NULL AND upload_expires_at IS NULL)
      OR (status = 'UPLOAD_PENDING' AND upload_object_key_digest IS NOT NULL AND upload_object_version IS NULL AND upload_expires_at IS NOT NULL)
      OR (status IN ('UPLOADED','VALIDATING','STAGING','READY_TO_PUBLISH','PUBLISHED','FAILED_RETRYABLE','REJECTED')
          AND upload_object_key_digest IS NOT NULL AND upload_object_version IS NOT NULL)
      OR status = 'CANCELLED')
    AND ((status IN ('VALIDATING','STAGING') AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
      OR (status NOT IN ('VALIDATING','STAGING') AND lease_owner IS NULL AND lease_until IS NULL))
    AND ((status = 'CANCELLED' AND cancelled_at IS NOT NULL)
      OR (status <> 'CANCELLED' AND cancelled_at IS NULL))
);
ALTER TABLE td_bulk_import_scopes ADD CONSTRAINT chk_td_bulk_import_scope_values CHECK (
    declared_row_count >= 0
    AND (
        (scope_as_of IS NOT NULL AND cursor_start IS NULL
            AND ((cursor_end IS NULL AND source_sequence_start IS NULL AND source_sequence_end IS NULL)
              OR (cursor_end IS NOT NULL AND source_sequence_start IS NULL AND source_sequence_end IS NOT NULL AND source_sequence_end >= 0)))
        OR (scope_as_of IS NULL AND cursor_start IS NOT NULL AND cursor_end IS NOT NULL
            AND ((source_sequence_start IS NULL AND source_sequence_end IS NULL)
              OR (source_sequence_start IS NOT NULL AND source_sequence_end IS NOT NULL
                  AND source_sequence_start >= 0 AND source_sequence_end >= source_sequence_start)))
    )
);
ALTER TABLE tenants ADD CONSTRAINT chk_tenants_status CHECK (status IN ('ACTIVE', 'INACTIVE'));
ALTER TABLE track1_purchase_ledger_batches ADD CONSTRAINT chk_t1_ledger_batch_values CHECK (
        status IN ('ACCEPTED', 'REJECTED')
        AND input_type IN ('INDIVIDUAL_TICKET', 'ACCOUNT_LIMIT_SNAPSHOT')
        AND ledger_version >= 1 AND input_row_count >= 0 AND accepted_row_count >= 0 AND rejected_row_count >= 0
        AND (status <> 'ACCEPTED' OR (input_type = 'INDIVIDUAL_TICKET' AND accepted_source_version = source_version
            AND rows_digest IS NOT NULL AND quantity_basis IS NOT NULL
            AND accepted_row_count >= 1 AND accepted_row_count = input_row_count AND rejected_row_count = 0))
        AND (status <> 'REJECTED' OR (accepted_source_version IS NULL AND accepted_row_count = 0))
    );
ALTER TABLE track1_purchase_ledger_errors ADD CONSTRAINT chk_t1_ledger_error_index CHECK (row_index IS NULL OR row_index >= 0);
ALTER TABLE track1_purchase_ledger_rows ADD CONSTRAINT chk_t1_ledger_row_values CHECK (
        source_row_index >= 0 AND ticket_quantity >= 1 AND purchased_at <= source_as_of
        AND quantity_unit IN ('TICKET', 'ORDER_LINE') AND (quantity_unit <> 'TICKET' OR ticket_quantity = 1)
        AND ticket_status IN ('ACTIVE', 'CANCELLED', 'REFUNDED', 'UNKNOWN')
        AND (payment_hmac IS NOT NULL OR address_hmac IS NOT NULL OR device_hmac IS NOT NULL OR ip_hmac IS NOT NULL)
    );
ALTER TABLE track1_purchase_ledger_rows ADD CONSTRAINT chk_t1_ledger_track2_reference CHECK (track2_reservation_ref_hmac IS NULL OR CHAR_LENGTH(track2_reservation_ref_hmac) = 64);
ALTER TABLE track1_purchase_limit_finding_reservations ADD CONSTRAINT chk_t1_limit_finding_reservation CHECK (effective_ticket_quantity >= 1);
ALTER TABLE track1_purchase_limit_findings ADD CONSTRAINT chk_t1_limit_finding_values CHECK (effective_ticket_quantity >= 1 AND max_tickets_per_account >= 1);
ALTER TABLE track1_purchase_limit_run_clusters ADD CONSTRAINT chk_t1_limit_run_cluster CHECK (risk_score >= 0 AND risk_score <= 1 AND member_count >= 1);
ALTER TABLE track1_purchase_limit_runs ADD CONSTRAINT chk_t1_limit_run_values CHECK (
        execution_status IN ('IN_PROGRESS', 'SUCCEEDED', 'INPUT_REJECTED', 'ANALYZER_UNAVAILABLE', 'ANALYZER_FAILED', 'INTERRUPTED')
        AND (cluster_count IS NULL OR cluster_count >= 0) AND netted_excluded_row_count >= 0
        AND ((ledger_batch_id IS NOT NULL) <> (ledger_version_id IS NOT NULL))
        AND ((execution_status = 'IN_PROGRESS') = ((active_ledger_batch_id IS NOT NULL) <> (active_ledger_version_id IS NOT NULL)))
        AND (execution_status <> 'SUCCEEDED' OR (evaluation_status IS NOT NULL AND cluster_count IS NOT NULL AND error_code IS NULL))
        AND (execution_status IN ('IN_PROGRESS', 'SUCCEEDED') OR (error_code IS NOT NULL AND evaluation_status IS NULL))
    );
ALTER TABLE track1_purchase_limit_shared_signal_observations ADD CONSTRAINT chk_t1_signal_observation_values CHECK (
        signal_type IN ('IP', 'ADDRESS') AND ticket_quantity_status IN ('VERIFIED', 'UNAVAILABLE')
        AND distinct_account_count >= 0 AND scope_account_count >= 0 AND distinct_reservation_count >= 0
        AND payment_shared_account_count >= 0 AND device_shared_account_count >= 0
        AND (purchase_window_seconds IS NULL OR purchase_window_seconds >= 0)
        AND ((ticket_quantity_status = 'VERIFIED' AND total_ticket_quantity >= 0)
             OR (ticket_quantity_status = 'UNAVAILABLE' AND total_ticket_quantity IS NULL))
    );
ALTER TABLE verified_performances ADD CONSTRAINT chk_verified_performance_status CHECK (
        (status = 'ACTIVE' AND withdrawn_by_membership_id IS NULL AND withdrawn_at IS NULL AND withdrawal_reason IS NULL)
        OR (status = 'WITHDRAWN' AND withdrawn_by_membership_id IS NOT NULL AND withdrawn_at IS NOT NULL
            AND withdrawal_reason IS NOT NULL));
ALTER TABLE verified_performances ADD CONSTRAINT chk_verified_performance_values CHECK (
        CHAR_LENGTH(TRIM(performance_ref)) > 0 AND CHAR_LENGTH(TRIM(verification_basis)) > 0
        AND CHAR_LENGTH(TRIM(source_version)) > 0 AND revision >= 1);
