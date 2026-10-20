-- Apply after admin-membership-event-migration.sql. Existing events remain closed to public intake.
ALTER TABLE events
    ADD COLUMN public_intake_enabled BIT(1) NOT NULL DEFAULT b'0';

CREATE TABLE fan_reports (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    receipt_number VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    submitted_performance_at DATETIME(6) NOT NULL,
    verified_performance_ref VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    seat_area VARCHAR(160) NULL,
    report_type ENUM('PREMIUM_RESALE', 'AUTOMATION_OFFER', 'FAKE_TICKET', 'OTHER') NOT NULL,
    description TEXT NOT NULL,
    listing_url VARCHAR(2048) NULL,
    platform VARCHAR(160) NULL,
    seller VARCHAR(160) NULL,
    asking_price DECIMAL(14,2) NULL,
    received_at DATETIME(6) NOT NULL,
    retention_until DATETIME(6) NOT NULL,
    intake_status ENUM('PENDING_STORAGE', 'RECEIVED', 'STORAGE_FAILED', 'ANALYSIS_PENDING',
        'ANALYSIS_FAILED', 'UNVERIFIABLE') NOT NULL,
    idempotency_key_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    lookup_secret_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fan_reports_receipt (receipt_number),
    UNIQUE KEY uk_fan_reports_idempotency (idempotency_key_digest),
    KEY idx_fan_reports_scope_received (tenant_id, event_id, received_at),
    CONSTRAINT fk_fan_reports_event_scope FOREIGN KEY (tenant_id, event_id)
        REFERENCES events (tenant_id, id),
    CONSTRAINT chk_fan_reports_asking_price CHECK (asking_price IS NULL OR asking_price > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE evidence_assets (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fan_report_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_key VARCHAR(256) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    media_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    byte_size BIGINT NOT NULL,
    sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_version VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NULL,
    storage_status ENUM('PENDING', 'STORED', 'FAILED') NOT NULL,
    created_at DATETIME(6) NOT NULL,
    retention_until DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_evidence_assets_object_key (object_key),
    KEY idx_evidence_assets_report (fan_report_id),
    CONSTRAINT fk_evidence_assets_report FOREIGN KEY (fan_report_id) REFERENCES fan_reports (id),
    CONSTRAINT chk_evidence_assets_size CHECK (byte_size > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Shared by all application nodes. The source address comes from the trusted ingress.
CREATE TABLE public_request_quota (
    scope VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    window_started_at DATETIME(6) NOT NULL,
    attempts INT NOT NULL,
    PRIMARY KEY (scope, actor_digest),
    CONSTRAINT chk_public_request_quota_attempts CHECK (attempts > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
