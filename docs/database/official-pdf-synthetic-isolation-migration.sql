-- Issue #66 공식 PDF 합성 격리 고객사 등록부입니다. admin-evidence-content-audit-migration.sql 다음에 적용합니다.
-- 기존 테이블은 바꾸지 않고 새 테이블만 추가합니다. 이 마이그레이션은 등록 행을 넣지 않으므로 운영 DB에서는 발급이 계속 차단됩니다.
-- 등록은 합성 격리 환경을 준비하는 절차만 수행하며 공개 API로 만들 수 없습니다. 등록 행은 합성 여부를 증명하지 않습니다.
CREATE TABLE official_pdf_synthetic_tenants (
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    -- 서버 설정 ticket-defender.official-pdf.synthetic-isolation-id와 같아야 합니다. 다른 격리 환경의 등록은 인정하지 않습니다.
    isolation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    -- UTC 기준 시각입니다. 애플리케이션의 Case, 제보, 증거, 출처 시각도 UTC LocalDateTime이며 이 시각 이전에 만들어진
    -- 데이터는 합성 발급 근거로 쓰지 않습니다. 등록 절차는 이 열을 생략해 기본값 UTC_TIMESTAMP(6)를 사용합니다.
    -- NOW()나 CURRENT_TIMESTAMP는 세션 시간대를 따르므로 음수 오프셋 세션에서 등록 시각이 과거로 기록될 수 있어 사용하지 않습니다.
    registered_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    PRIMARY KEY (tenant_id),
    CONSTRAINT fk_official_pdf_synthetic_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT chk_official_pdf_synthetic_isolation
        CHECK (isolation_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 격리 환경 등록 예시입니다. registered_at은 생략해 서버 UTC 시각을 쓰고, 합성 데이터는 등록 뒤에 적재합니다.
-- INSERT INTO official_pdf_synthetic_tenants (tenant_id, isolation_id) VALUES ('<합성 고객사 UUID>', '<격리 식별자 UUID>');
