-- Additive MySQL 8.0 migration for Issue #79. Run only in an isolated, approved schema; do not modify A3 data.
CREATE TABLE purchase_limit_policies (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, active_performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL, max_tickets_per_account INT NOT NULL,
    policy_version VARCHAR(128) NOT NULL, effective_from DATETIME(6) NOT NULL, effective_until DATETIME(6) NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, policy_source VARCHAR(512) NOT NULL, verification_basis VARCHAR(512) NOT NULL,
    changed_by_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, changed_at DATETIME(6) NOT NULL,
    revision INT NOT NULL, row_version BIGINT NOT NULL, PRIMARY KEY (id),
    UNIQUE KEY uk_purchase_limit_policy_active_scope (tenant_id,event_id,active_performance_id), UNIQUE KEY uk_purchase_limit_policy_version_scope (tenant_id,event_id,performance_id,policy_version), KEY idx_purchase_limit_policy_scope_status (tenant_id,event_id,status),
    CONSTRAINT fk_purchase_limit_policy_performance FOREIGN KEY (tenant_id,event_id,performance_id) REFERENCES verified_performances (tenant_id,event_id,id),
    CONSTRAINT fk_purchase_limit_policy_actor FOREIGN KEY (tenant_id,changed_by_membership_id) REFERENCES memberships (tenant_id,id),
    CONSTRAINT chk_purchase_limit_policy_values CHECK (max_tickets_per_account >= 1 AND revision >= 1 AND status IN ('ACTIVE','WITHDRAWN') AND (effective_until IS NULL OR effective_until > effective_from))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE purchase_limit_policy_audits (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, policy_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    action VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    max_tickets_per_account INT NOT NULL, policy_version VARCHAR(128) NOT NULL, effective_from DATETIME(6) NOT NULL, effective_until DATETIME(6) NULL,
    policy_source VARCHAR(512) NOT NULL, verification_basis VARCHAR(512) NOT NULL, revision INT NOT NULL,
    actor_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY(id), UNIQUE KEY uk_purchase_limit_policy_audit_revision(policy_id,revision),
    CONSTRAINT fk_purchase_limit_policy_audit_policy FOREIGN KEY(policy_id) REFERENCES purchase_limit_policies(id),
    CONSTRAINT chk_purchase_limit_policy_audit_values CHECK (max_tickets_per_account >= 1 AND revision >= 1 AND action IN ('CREATED','REVISED','WITHDRAWN') AND status IN ('ACTIVE','WITHDRAWN') AND (effective_until IS NULL OR effective_until > effective_from))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
