-- MEI-05: 같은 공연의 서로 다른 회차를 반복 근거로 인정하는 다회차 점수 버전입니다.
-- 적용 순서: multi-event-artifact-job-migration.sql 다음.
-- 새 실행은 score-v2-exp2-multi-performance-recurrence만 저장합니다. 이전 값은 이미 저장된 실행을
-- 읽기 전용으로 보존하기 위해서만 허용합니다.
ALTER TABLE td_multi_event_investigation_runs
    DROP CHECK chk_mei_run_state,
    ADD CONSTRAINT chk_mei_run_state CHECK (
        hmac_purpose = 'TRACK1_CONNECTION'
        AND score_version IN ('score-v2-exp2-multi-performance-recurrence','score-v2-exp2-multi-event-recurrence')
        AND projection_version = 'projection-v2.2-exp2-ipwindow-softhub'
        AND analysis_end_at > analysis_start_at AND analysis_end_at <= analysis_as_of
        AND status IN ('PENDING','RUNNING','DATA_PENDING','RETRYABLE','FAILED','BLOCKED','COMPLETED')
        AND fence_token >= 0 AND attempt_count >= 0 AND row_version >= 0
        AND ((status = 'RUNNING') = (lease_owner IS NOT NULL AND lease_until IS NOT NULL))
        AND ((status = 'COMPLETED') = (completed_at IS NOT NULL))
        AND ((status = 'COMPLETED') = (analysis_algorithm_version IS NOT NULL))
    );

-- 같은 공연 다회차 후보는 event가 1개일 수 있습니다. 회차는 여전히 2개 이상입니다.
ALTER TABLE td_multi_event_investigation_candidates
    DROP CHECK chk_mei_candidate_values,
    ADD CONSTRAINT chk_mei_candidate_values CHECK (
        priority_band IN ('HIGH','MEDIUM','LOW') AND investigation_score >= 0 AND investigation_score <= 1
        AND (baseline_max_score IS NULL OR (baseline_max_score >= 0 AND baseline_max_score <= 1))
        AND distinct_account_count >= 2 AND distinct_event_count >= 1 AND distinct_performance_count >= 2
        AND verified_relationship_count >= 1
    );
