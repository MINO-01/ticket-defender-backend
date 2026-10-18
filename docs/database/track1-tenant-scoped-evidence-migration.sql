-- 기존 고객사 미확인 증거는 tenant_id, internal_event_id를 NULL로 유지합니다.
-- admin-membership-event-migration.sql 적용 후 한 번 실행합니다.

CREATE TABLE service_tenant_bindings (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    issuer VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    client_id VARCHAR(191) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    subject VARCHAR(191) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_service_tenant_identity (issuer, client_id, subject),
    KEY idx_service_tenant_bindings_tenant (tenant_id),
    CONSTRAINT fk_service_tenant_bindings_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT chk_service_tenant_bindings_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

ALTER TABLE macro_audit_evidence
    ADD COLUMN tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN internal_event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD KEY idx_macro_audit_evidence_scope (tenant_id, internal_event_id),
    ADD CONSTRAINT fk_macro_audit_evidence_event_scope
        FOREIGN KEY (tenant_id, internal_event_id) REFERENCES events (tenant_id, id),
    ADD CONSTRAINT chk_macro_audit_evidence_scope
        CHECK ((tenant_id IS NULL AND internal_event_id IS NULL)
            OR (tenant_id IS NOT NULL AND internal_event_id IS NOT NULL));

-- 마이그레이션 직후 기존 행은 모두 미귀속 상태여야 합니다.
SELECT COUNT(*) AS unattributed_evidence_count
FROM macro_audit_evidence
WHERE tenant_id IS NULL AND internal_event_id IS NULL;
