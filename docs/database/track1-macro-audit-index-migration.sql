-- 이전 Track 1 버전의 MySQL DB에서 한 번 실행합니다.
-- ticket_audit에 매크로 전용 컬럼이 추가된 상태여야 합니다.
-- 사전 점검 결과가 있으면 중복·누락 데이터를 먼저 정리합니다.

-- 공연·예매·계정 조합이 중복된 매크로 내역
SELECT event_id, reservation_no, account_id, COUNT(*) AS duplicate_count
FROM ticket_audit
WHERE evidence_type = 'MACRO_GRAPH'
  AND event_id IS NOT NULL
  AND reservation_no IS NOT NULL
  AND account_id IS NOT NULL
GROUP BY event_id, reservation_no, account_id
HAVING COUNT(*) > 1;

-- 새 테이블로 옮길 분석 정보가 빠진 매크로 내역
SELECT id, event_id, reservation_no, account_id, cluster_id,
       analysis_algorithm, analysis_algorithm_version, analysis_request_id,
       analysis_completed_at
FROM ticket_audit
WHERE evidence_type = 'MACRO_GRAPH'
  AND (event_id IS NULL OR reservation_no IS NULL OR account_id IS NULL
       OR cluster_id IS NULL OR risk_score IS NULL OR analysis_algorithm IS NULL
       OR analysis_algorithm_version IS NULL OR analysis_request_id IS NULL
       OR analysis_completed_at IS NULL);

-- 매크로 중복 키는 전용 테이블에서 관리합니다.
-- 팬 제보가 쌓이는 ticket_audit에는 매크로 인덱스 갱신 비용을 추가하지 않습니다.
CREATE TABLE macro_audit_evidence (
    audit_id BIGINT NOT NULL,
    evidence_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id VARCHAR(255) NOT NULL,
    reservation_no VARCHAR(255) NOT NULL,
    account_id VARCHAR(255) NOT NULL,
    device_id_hash VARCHAR(255) NULL,
    ip_hash VARCHAR(255) NULL,
    cluster_id VARCHAR(255) NOT NULL,
    risk_score DOUBLE NOT NULL,
    analysis_algorithm VARCHAR(255) NOT NULL,
    analysis_algorithm_version VARCHAR(255) NOT NULL,
    analysis_request_id VARCHAR(255) NOT NULL,
    analysis_completed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (audit_id),
    UNIQUE KEY uk_macro_audit_evidence_key (evidence_key),
    CONSTRAINT fk_macro_audit_evidence_ticket_audit
        FOREIGN KEY (audit_id) REFERENCES ticket_audit (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 아래 키 생성식은 MacroEvidenceKey.create()와 같은 규칙을 사용합니다.
-- UTF-8 바이트 길이와 값을 event_id, reservation_no, account_id 순서로 연결한 뒤 SHA-256을 계산합니다.
-- 기존 매크로 분석 정보를 전용 테이블로 옮깁니다.
INSERT INTO macro_audit_evidence (
    audit_id, evidence_key, event_id, reservation_no, account_id,
    device_id_hash, ip_hash, cluster_id, risk_score, analysis_algorithm,
    analysis_algorithm_version, analysis_request_id, analysis_completed_at
)
SELECT
    id,
    LOWER(SHA2(CONCAT(
        OCTET_LENGTH(CONVERT(event_id USING utf8mb4)), ':', CONVERT(event_id USING utf8mb4),
        OCTET_LENGTH(CONVERT(reservation_no USING utf8mb4)), ':', CONVERT(reservation_no USING utf8mb4),
        OCTET_LENGTH(CONVERT(account_id USING utf8mb4)), ':', CONVERT(account_id USING utf8mb4)
    ), 256)),
    event_id, reservation_no, account_id,
    device_id_hash, ip_hash, cluster_id, risk_score, analysis_algorithm,
    analysis_algorithm_version, analysis_request_id, analysis_completed_at
FROM ticket_audit
WHERE evidence_type = 'MACRO_GRAPH'
  AND event_id IS NOT NULL
  AND reservation_no IS NOT NULL
  AND account_id IS NOT NULL
  AND cluster_id IS NOT NULL
  AND risk_score IS NOT NULL
  AND analysis_algorithm IS NOT NULL
  AND analysis_algorithm_version IS NOT NULL
  AND analysis_request_id IS NOT NULL
  AND analysis_completed_at IS NOT NULL;

-- 기존 결제 수단·계정 유니크 키를 제거합니다. 매크로 중복 여부는 공연·예매·계정 조합으로 판단합니다.
ALTER TABLE ticket_audit DROP INDEX uk_payment_account;
ALTER TABLE ticket_audit DROP INDEX idx_ticket_audit_event_reservation_evidence;
CREATE INDEX idx_ticket_audit_reservation_evidence_status
    ON ticket_audit (reservation_no, evidence_type, status);

-- 롤백과 기존 데이터 확인을 위해 이전 컬럼은 당분간 유지합니다.
-- 새 애플리케이션은 전용 테이블을 사용하며, 이전 컬럼은 별도 마이그레이션에서 정리합니다.
