-- Additive source metadata for retention-bounded exp3 E2 observations.
-- Apply after bulk-ledger-import-v3-raw-signal-migration.sql and
-- bulk-ledger-score-v2-exp2-signal-migration.sql in an approved isolated schema only.
-- This file is not applied by this change.
ALTER TABLE td_bulk_import_staging_rows
    ADD COLUMN device_observed_at DATETIME(6) NULL,
    ADD COLUMN refund_account_observed_at DATETIME(6) NULL,
    ADD CONSTRAINT chk_td_bulk_staging_device_observation CHECK (
        device_observed_at IS NULL OR device_hmac IS NOT NULL
    ),
    ADD CONSTRAINT chk_td_bulk_staging_refund_observation CHECK (
        refund_account_observed_at IS NULL OR refund_account_hmac IS NOT NULL
    );

ALTER TABLE td_bulk_ledger_track1_ticket_signals
    ADD COLUMN device_observed_at DATETIME(6) NULL AFTER device_hmac,
    ADD COLUMN refund_account_observed_at DATETIME(6) NULL AFTER refund_account_hmac,
    ADD CONSTRAINT chk_td_bl_signal_device_observation CHECK (
        device_observed_at IS NULL OR device_hmac IS NOT NULL
    ),
    ADD CONSTRAINT chk_td_bl_signal_refund_observation CHECK (
        refund_account_observed_at IS NULL OR refund_account_hmac IS NOT NULL
    );

CREATE TABLE td_exp3_resource_retention_policies (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    supplier_namespace VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    resource_type VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    policy_version VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    retention_days INT NOT NULL,
    effective_from DATETIME(6) NOT NULL,
    effective_until DATETIME(6) NULL,
    policy_source VARCHAR(512) NOT NULL,
    verification_basis VARCHAR(512) NOT NULL,
    approved_by_membership_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    approved_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_exp3_retention_scope_version
        (tenant_id,event_id,supplier_namespace,resource_type,policy_version),
    KEY idx_exp3_retention_active
        (tenant_id,event_id,supplier_namespace,resource_type,effective_from,effective_until),
    CONSTRAINT chk_exp3_retention_resource_type CHECK (resource_type = 'refund_account_hash'),
    CONSTRAINT chk_exp3_retention_days CHECK (retention_days > 0),
    CONSTRAINT chk_exp3_retention_period CHECK (effective_until IS NULL OR effective_until > effective_from),
    CONSTRAINT fk_exp3_retention_event FOREIGN KEY (tenant_id,event_id) REFERENCES events (tenant_id,id),
    CONSTRAINT fk_exp3_retention_actor FOREIGN KEY (tenant_id,approved_by_membership_id) REFERENCES memberships (tenant_id,id)
);
