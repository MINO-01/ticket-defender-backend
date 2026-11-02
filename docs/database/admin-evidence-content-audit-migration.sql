-- Issue #64 관리자 원본 이미지 열람 감사 기록입니다. track2-listing-analysis-migration.sql 다음에 적용합니다.
-- 기존 테이블은 바꾸지 않고 새 테이블만 추가합니다. 객체 키, 객체 버전, 이미지 바이트, 인증 토큰은 저장하지 않습니다.
CREATE TABLE evidence_access_audit_events (
    -- 요청마다 서버가 만든 ID입니다. 한 요청에는 성공이나 실패 중 하나의 기록만 남습니다.
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fan_report_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    -- 요청한 증거가 이 제보 소속으로 확인되지 않은 실패에서는 비워 둡니다.
    evidence_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    actor_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    outcome VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    detail_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_evidence_access_audit_scope_time (tenant_id, event_id, fan_report_id, occurred_at),
    KEY idx_evidence_access_audit_evidence_time (evidence_id, occurred_at),
    KEY idx_evidence_access_audit_report_evidence (fan_report_id, evidence_id),
    KEY idx_evidence_access_audit_actor (tenant_id, actor_membership_id),
    CONSTRAINT fk_evidence_access_audit_report FOREIGN KEY (tenant_id, event_id, fan_report_id)
        REFERENCES fan_reports (tenant_id, event_id, id),
    CONSTRAINT fk_evidence_access_audit_asset FOREIGN KEY (fan_report_id, evidence_id)
        REFERENCES evidence_assets (fan_report_id, id),
    CONSTRAINT fk_evidence_access_audit_actor FOREIGN KEY (tenant_id, actor_membership_id)
        REFERENCES memberships (tenant_id, id),
    CONSTRAINT chk_evidence_access_audit_outcome CHECK (outcome IN ('SUCCEEDED', 'FAILED')),
    -- 성공 기록은 확인된 증거에만 남고, 성공 코드는 실패 기록에 쓸 수 없습니다.
    CONSTRAINT chk_evidence_access_audit_result CHECK (
        (outcome = 'SUCCEEDED' AND detail_code = 'VERIFIED_FOR_RESPONSE' AND evidence_id IS NOT NULL)
        OR (outcome = 'FAILED' AND detail_code <> 'VERIFIED_FOR_RESPONSE')),
    CONSTRAINT chk_evidence_access_audit_code CHECK (detail_code IN ('VERIFIED_FOR_RESPONSE',
        'RESOURCE_NOT_FOUND', 'ACCESS_DENIED', 'TENANT_SELECTION_REQUIRED', 'SOURCE_NOT_AVAILABLE',
        'RETENTION_EXPIRED', 'UNSUPPORTED_IMAGE_TYPE', 'EVIDENCE_CONTENT_TOO_LARGE', 'EVIDENCE_CONTENT_BUSY',
        'OBJECT_CHANGED', 'OBJECT_MISSING', 'STORAGE_UNAVAILABLE', 'SIZE_MISMATCH', 'MEDIA_TYPE_MISMATCH',
        'HASH_MISMATCH', 'SOURCE_CHANGED', 'AUDIT_UNAVAILABLE', 'INTERNAL_ERROR'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
