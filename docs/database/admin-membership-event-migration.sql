-- 기존 업무 테이블과 데이터는 변경하지 않습니다. 신규 권한 테이블만 추가합니다.

CREATE TABLE IF NOT EXISTS tenants (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(160) NOT NULL,
    status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_tenants_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS events (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    external_event_ref VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    name VARCHAR(255) NOT NULL,
    status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_events_tenant_external (tenant_id, external_event_ref),
    UNIQUE KEY idx_events_tenant_id (tenant_id, id),
    CONSTRAINT fk_events_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT chk_events_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS memberships (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    subject VARCHAR(191) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_memberships_tenant_subject (tenant_id, subject),
    UNIQUE KEY idx_memberships_tenant_id (tenant_id, id),
    CONSTRAINT fk_memberships_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT chk_memberships_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS membership_roles (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    role VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_membership_roles_member_role (membership_id, role),
    CONSTRAINT fk_membership_roles_member FOREIGN KEY (membership_id) REFERENCES memberships (id),
    CONSTRAINT chk_membership_roles_role CHECK (role IN ('ANALYST', 'REVIEWER', 'APPROVER', 'TENANT_ADMIN'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS membership_events (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_membership_events_member_event (membership_id, event_id),
    KEY idx_membership_events_tenant_member (tenant_id, membership_id),
    KEY idx_membership_events_tenant_event (tenant_id, event_id),
    CONSTRAINT fk_membership_events_member_scope
        FOREIGN KEY (tenant_id, membership_id) REFERENCES memberships (tenant_id, id),
    CONSTRAINT fk_membership_events_event_scope
        FOREIGN KEY (tenant_id, event_id) REFERENCES events (tenant_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
