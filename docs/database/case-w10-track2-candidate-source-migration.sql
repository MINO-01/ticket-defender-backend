-- Apply once after case-w09-official-pdf-migration.sql and track2-candidate-pipeline-migration.sql.
-- Additive: existing Case sources and schema-v1 snapshots are preserved unchanged.

ALTER TABLE case_source_references
    MODIFY source_type ENUM('STORED_CLUSTER_EVIDENCE','FAN_REPORT','EVIDENCE_ASSET','LISTING_CANDIDATE_MATCH') NOT NULL,
    ADD COLUMN listing_candidate_match_run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER object_marker,
    ADD COLUMN candidate_analysis_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER listing_candidate_match_run_id,
    ADD COLUMN candidate_analysis_row_version BIGINT NULL AFTER candidate_analysis_id,
    ADD COLUMN candidate_analysis_response_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER candidate_analysis_row_version,
    ADD COLUMN candidate_evidence_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER candidate_analysis_response_sha256,
    ADD COLUMN candidate_performance_link_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER candidate_evidence_sha256,
    ADD COLUMN candidate_performance_link_revision INT NULL AFTER candidate_performance_link_id,
    ADD COLUMN candidate_verified_performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER candidate_performance_link_revision,
    ADD COLUMN candidate_verified_performance_revision INT NULL AFTER candidate_verified_performance_id,
    ADD COLUMN candidate_reservation_source_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER candidate_verified_performance_revision,
    ADD COLUMN candidate_reservation_source_revision INT NULL AFTER candidate_reservation_source_id,
    ADD COLUMN candidate_reservation_batch_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER candidate_reservation_source_revision,
    ADD COLUMN candidate_reservation_source_version VARCHAR(128) NULL AFTER candidate_reservation_batch_id,
    ADD COLUMN candidate_status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER candidate_reservation_source_version,
    ADD COLUMN candidate_reason VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER candidate_status,
    ADD COLUMN candidate_count INT NULL AFTER candidate_reason,
    ADD COLUMN candidate_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER candidate_count,
    ADD KEY idx_case_sources_candidate_run (tenant_id, event_id, listing_candidate_match_run_id),
    ADD CONSTRAINT fk_case_sources_candidate_run FOREIGN KEY (tenant_id, event_id, listing_candidate_match_run_id)
        REFERENCES listing_candidate_match_runs (tenant_id, event_id, id);

ALTER TABLE case_source_references DROP CHECK chk_case_source_shape;
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

ALTER TABLE evidence_snapshots DROP CHECK chk_evidence_snapshots_schema;
ALTER TABLE evidence_snapshots ADD CONSTRAINT chk_evidence_snapshots_schema
    CHECK (manifest_schema_version IN (1, 2));
