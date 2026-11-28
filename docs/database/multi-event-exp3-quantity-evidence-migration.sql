-- Additive reference to the separately stored exp3 quantity/rebooking input sidecar.
-- Apply after multi-event-artifact-job-migration.sql. This does not apply the migration or enable exp3 scoring.
ALTER TABLE td_multi_event_investigation_runs
    ADD COLUMN exp3_quantity_artifact_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN exp3_quantity_artifact_object_version VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN exp3_quantity_artifact_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN exp3_quantity_artifact_record_count INT NULL,
    ADD CONSTRAINT chk_mei_exp3_quantity_artifact_binding CHECK (
        (exp3_quantity_artifact_id IS NULL AND exp3_quantity_artifact_object_version IS NULL
            AND exp3_quantity_artifact_sha256 IS NULL AND exp3_quantity_artifact_record_count IS NULL)
        OR (artifact_id IS NOT NULL AND exp3_quantity_artifact_id IS NOT NULL
            AND exp3_quantity_artifact_object_version IS NOT NULL
            AND exp3_quantity_artifact_sha256 REGEXP '^[0-9a-f]{64}$'
            AND exp3_quantity_artifact_record_count BETWEEN 1 AND 1200500)
    );
