-- Apply once after case-w10-track2-candidate-source-migration.sql and multi-event-investigation-migration.sql.
-- Additive: existing case sources and snapshots are retained without backfill.

ALTER TABLE td_multi_event_investigation_runs
    ADD UNIQUE KEY uk_mei_run_tenant_id (tenant_id, id);

ALTER TABLE case_source_references
    MODIFY source_type ENUM('STORED_CLUSTER_EVIDENCE','FAN_REPORT','EVIDENCE_ASSET',
        'LISTING_CANDIDATE_MATCH','MULTI_EVENT_CANDIDATE') NOT NULL,
    ADD COLUMN multi_event_run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER candidate_id,
    ADD COLUMN multi_event_candidate_id CHAR(70) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER multi_event_run_id,
    ADD COLUMN multi_event_completed_at DATETIME(6) NULL AFTER multi_event_candidate_id,
    ADD KEY idx_case_sources_multi_event_run (tenant_id, multi_event_run_id),
    ADD CONSTRAINT fk_case_sources_multi_event_run FOREIGN KEY (tenant_id, multi_event_run_id)
        REFERENCES td_multi_event_investigation_runs (tenant_id, id),
    ADD CONSTRAINT fk_case_sources_multi_event_candidate FOREIGN KEY (multi_event_run_id, multi_event_candidate_id)
        REFERENCES td_multi_event_investigation_candidates (run_id, candidate_id);

ALTER TABLE case_source_references DROP CHECK chk_case_source_shape;
ALTER TABLE case_source_references ADD CONSTRAINT chk_case_source_shape CHECK (
    (source_type = 'STORED_CLUSTER_EVIDENCE' AND analysis_request_id IS NOT NULL
        AND cluster_id IS NOT NULL AND anchor_audit_id IS NOT NULL
        AND fan_report_id IS NULL AND evidence_id IS NULL AND listing_candidate_match_run_id IS NULL
        AND multi_event_run_id IS NULL AND multi_event_candidate_id IS NULL AND multi_event_completed_at IS NULL)
    OR (source_type = 'FAN_REPORT' AND fan_report_id IS NOT NULL
        AND evidence_id IS NULL AND anchor_audit_id IS NULL
        AND analysis_request_id IS NULL AND cluster_id IS NULL AND listing_candidate_match_run_id IS NULL
        AND multi_event_run_id IS NULL AND multi_event_candidate_id IS NULL AND multi_event_completed_at IS NULL)
    OR (source_type = 'EVIDENCE_ASSET' AND fan_report_id IS NOT NULL
        AND evidence_id IS NOT NULL AND anchor_audit_id IS NULL
        AND analysis_request_id IS NULL AND cluster_id IS NULL AND listing_candidate_match_run_id IS NULL
        AND multi_event_run_id IS NULL AND multi_event_candidate_id IS NULL AND multi_event_completed_at IS NULL)
    OR (source_type = 'LISTING_CANDIDATE_MATCH' AND fan_report_id IS NOT NULL AND evidence_id IS NOT NULL
        AND listing_candidate_match_run_id IS NOT NULL AND candidate_analysis_id IS NOT NULL
        AND candidate_analysis_row_version IS NOT NULL AND candidate_analysis_response_sha256 IS NOT NULL
        AND candidate_evidence_sha256 IS NOT NULL AND candidate_performance_link_id IS NOT NULL
        AND candidate_performance_link_revision IS NOT NULL AND candidate_verified_performance_id IS NOT NULL
        AND candidate_verified_performance_revision IS NOT NULL AND candidate_reservation_source_id IS NOT NULL
        AND candidate_reservation_source_revision IS NOT NULL AND candidate_reservation_batch_id IS NOT NULL
        AND candidate_reservation_source_version IS NOT NULL AND candidate_status IS NOT NULL
        AND candidate_reason IS NOT NULL AND (candidate_count IS NULL OR candidate_count >= 0)
        AND anchor_audit_id IS NULL AND analysis_request_id IS NULL AND cluster_id IS NULL
        AND multi_event_run_id IS NULL AND multi_event_candidate_id IS NULL AND multi_event_completed_at IS NULL)
    OR (source_type = 'MULTI_EVENT_CANDIDATE' AND multi_event_run_id IS NOT NULL
        AND multi_event_candidate_id IS NOT NULL AND multi_event_completed_at IS NOT NULL
        AND analysis_request_id IS NULL AND cluster_id IS NULL AND anchor_audit_id IS NULL
        AND analysis_algorithm IS NULL AND analysis_algorithm_version IS NULL AND analysis_completed_at IS NULL
        AND risk_score IS NULL AND stored_member_count IS NULL AND fan_report_id IS NULL
        AND evidence_id IS NULL AND object_marker IS NULL AND listing_candidate_match_run_id IS NULL
        AND candidate_analysis_id IS NULL AND candidate_analysis_row_version IS NULL
        AND candidate_analysis_response_sha256 IS NULL AND candidate_evidence_sha256 IS NULL
        AND candidate_performance_link_id IS NULL AND candidate_performance_link_revision IS NULL
        AND candidate_verified_performance_id IS NULL AND candidate_verified_performance_revision IS NULL
        AND candidate_reservation_source_id IS NULL AND candidate_reservation_source_revision IS NULL
        AND candidate_reservation_batch_id IS NULL AND candidate_reservation_source_version IS NULL
        AND candidate_status IS NULL AND candidate_reason IS NULL AND candidate_count IS NULL AND candidate_id IS NULL)
);

ALTER TABLE evidence_snapshots DROP CHECK chk_evidence_snapshots_schema;
ALTER TABLE evidence_snapshots ADD CONSTRAINT chk_evidence_snapshots_schema
    CHECK (manifest_schema_version IN (1, 2, 3));
