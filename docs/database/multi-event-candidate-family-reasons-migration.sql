-- PR 1: 표시용 후보 가족과 근거를 기존 다공연 결과에 추가합니다.
-- 후보 점수와 원장 데이터는 변경하지 않습니다. 기존 결과 행은 새 메타데이터가 NULL일 수 있습니다.

SET @td_schema = DATABASE();

SET @td_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@td_schema
      AND TABLE_NAME='td_multi_event_investigation_candidates' AND COLUMN_NAME='family_id')=0,
    'ALTER TABLE td_multi_event_investigation_candidates ADD COLUMN family_id CHAR(70) CHARACTER SET ascii COLLATE ascii_bin NULL',
    'SELECT 1');
PREPARE td_stmt FROM @td_ddl; EXECUTE td_stmt; DEALLOCATE PREPARE td_stmt;

SET @td_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@td_schema
      AND TABLE_NAME='td_multi_event_investigation_candidates' AND COLUMN_NAME='parent_candidate_id')=0,
    'ALTER TABLE td_multi_event_investigation_candidates ADD COLUMN parent_candidate_id CHAR(70) CHARACTER SET ascii COLLATE ascii_bin NULL',
    'SELECT 1');
PREPARE td_stmt FROM @td_ddl; EXECUTE td_stmt; DEALLOCATE PREPARE td_stmt;

SET @td_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@td_schema
      AND TABLE_NAME='td_multi_event_investigation_candidates' AND COLUMN_NAME='child_count')=0,
    'ALTER TABLE td_multi_event_investigation_candidates ADD COLUMN child_count INT NOT NULL DEFAULT 0',
    'SELECT 1');
PREPARE td_stmt FROM @td_ddl; EXECUTE td_stmt; DEALLOCATE PREPARE td_stmt;

SET @td_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@td_schema
      AND TABLE_NAME='td_multi_event_investigation_candidates' AND COLUMN_NAME='family_score')=0,
    'ALTER TABLE td_multi_event_investigation_candidates ADD COLUMN family_score DOUBLE NULL',
    'SELECT 1');
PREPARE td_stmt FROM @td_ddl; EXECUTE td_stmt; DEALLOCATE PREPARE td_stmt;

SET @td_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=@td_schema
      AND TABLE_NAME='td_multi_event_investigation_candidates' AND COLUMN_NAME='reasons_json')=0,
    'ALTER TABLE td_multi_event_investigation_candidates ADD COLUMN reasons_json JSON NULL',
    'SELECT 1');
PREPARE td_stmt FROM @td_ddl; EXECUTE td_stmt; DEALLOCATE PREPARE td_stmt;

SET @td_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=@td_schema
      AND TABLE_NAME='td_multi_event_investigation_candidates' AND INDEX_NAME='idx_mei_candidates_family')=0,
    'CREATE INDEX idx_mei_candidates_family ON td_multi_event_investigation_candidates (run_id,family_score,family_id,candidate_id)',
    'SELECT 1');
PREPARE td_stmt FROM @td_ddl; EXECUTE td_stmt; DEALLOCATE PREPARE td_stmt;

SET @td_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=@td_schema
      AND TABLE_NAME='td_multi_event_investigation_members' AND INDEX_NAME='idx_mei_member_account_family')=0,
    'CREATE INDEX idx_mei_member_account_family ON td_multi_event_investigation_members (run_id,account_reference_hmac,candidate_id)',
    'SELECT 1');
PREPARE td_stmt FROM @td_ddl; EXECUTE td_stmt; DEALLOCATE PREPARE td_stmt;

CREATE TABLE IF NOT EXISTS td_multi_event_investigation_member_reasons (
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    candidate_id CHAR(70) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    account_reference_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reasons_json JSON NOT NULL,
    PRIMARY KEY (run_id,candidate_id,account_reference_hmac),
    CONSTRAINT fk_mei_member_reason_member FOREIGN KEY (run_id,candidate_id,account_reference_hmac)
        REFERENCES td_multi_event_investigation_members (run_id,candidate_id,account_reference_hmac) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
