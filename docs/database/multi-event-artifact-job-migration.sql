-- MEI-04: durable bridge from a fixed Spring analysis run to a private Data NDJSON job.
-- Apply after multi-event-investigation-migration.sql and its quantity/source migrations.
-- Existing rows retain their state and have NULL artifact/job metadata.
ALTER TABLE td_multi_event_investigation_runs
    ADD COLUMN data_job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN artifact_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN artifact_object_version VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN artifact_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN artifact_record_count INT NULL,
    ADD COLUMN artifact_cancelled_ticket_count INT NOT NULL DEFAULT 0,
    ADD UNIQUE KEY uk_mei_data_job (data_job_id),
    ADD CONSTRAINT chk_mei_cancelled_artifact_count CHECK (
        artifact_cancelled_ticket_count >= 0
        AND (artifact_record_count IS NULL OR artifact_cancelled_ticket_count < artifact_record_count)
    ),
    ADD CONSTRAINT chk_mei_artifact_binding CHECK (
        ((artifact_id IS NULL AND artifact_object_version IS NULL AND artifact_sha256 IS NULL
            AND artifact_record_count IS NULL AND data_job_id IS NULL)
         OR (artifact_id IS NOT NULL AND artifact_object_version IS NOT NULL
            AND artifact_sha256 REGEXP '^[0-9a-f]{64}$' AND artifact_record_count BETWEEN 1 AND 400000))
        AND (data_job_id IS NULL OR artifact_id IS NOT NULL)
    );

ALTER TABLE td_multi_event_investigation_runs
    DROP CHECK chk_mei_run_state,
    ADD CONSTRAINT chk_mei_run_state CHECK (
        hmac_purpose = 'TRACK1_CONNECTION'
        AND score_version = 'score-v2-exp2-multi-event-recurrence'
        AND projection_version = 'projection-v2.2-exp2-ipwindow-softhub'
        AND analysis_end_at > analysis_start_at AND analysis_end_at <= analysis_as_of
        AND status IN ('PENDING','RUNNING','DATA_PENDING','RETRYABLE','FAILED','BLOCKED','COMPLETED')
        AND fence_token >= 0 AND attempt_count >= 0 AND row_version >= 0
        AND ((status = 'RUNNING') = (lease_owner IS NOT NULL AND lease_until IS NOT NULL))
        AND ((status = 'COMPLETED') = (completed_at IS NOT NULL))
        AND ((status = 'COMPLETED') = (analysis_algorithm_version IS NOT NULL))
    );

CREATE INDEX idx_mei_data_claim ON td_multi_event_investigation_runs (status, data_job_id, lease_until, created_at);
