-- Enable the preregistered exp3 model on existing multi-event runs.
-- Apply after multi-event-exp3-quantity-evidence-migration.sql.
-- Keep both supported exp2 identifiers and their existing projection unchanged.
ALTER TABLE td_multi_event_investigation_runs
    DROP CHECK chk_mei_run_state,
    ADD CONSTRAINT chk_mei_run_state CHECK (
        hmac_purpose = 'TRACK1_CONNECTION'
        AND (
            (score_version IN (
                'score-v2-exp2-multi-performance-recurrence',
                'score-v2-exp2-multi-event-recurrence'
            ) AND projection_version = 'projection-v2.2-exp2-ipwindow-softhub')
            OR (score_version = 'score-v3-exp3-continuity'
                AND projection_version = 'projection-v3-exp3-continuity-ipwindow-2offset')
        )
        AND analysis_end_at > analysis_start_at AND analysis_end_at <= analysis_as_of
        AND status IN ('PENDING','RUNNING','DATA_PENDING','RETRYABLE','FAILED','BLOCKED','COMPLETED')
        AND fence_token >= 0 AND attempt_count >= 0 AND row_version >= 0
        AND ((status = 'RUNNING') = (lease_owner IS NOT NULL AND lease_until IS NOT NULL))
        AND ((status = 'COMPLETED') = (completed_at IS NOT NULL))
        AND ((status = 'COMPLETED') = (analysis_algorithm_version IS NOT NULL))
    );
