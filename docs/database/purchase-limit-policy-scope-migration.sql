-- Issue #79 additive MySQL 8.0 migration. Run after purchase-limit-policy-lifecycle-migration.sql
-- and purchase-limit-policy-semantics-migration.sql. Legacy active policies retain a performance scope key.
ALTER TABLE purchase_limit_policies
    ADD COLUMN policy_scope_key VARCHAR(256) NULL AFTER active_performance_id,
    ADD COLUMN active_scope_key VARCHAR(256) NULL AFTER policy_scope_key;

UPDATE purchase_limit_policies
    SET policy_scope_key = CONCAT('LEGACY:PERFORMANCE:', performance_id),
        active_scope_key = CASE WHEN status = 'ACTIVE' THEN CONCAT('LEGACY:PERFORMANCE:', performance_id) ELSE NULL END
    WHERE policy_scope_key IS NULL;

ALTER TABLE purchase_limit_policies
    MODIFY COLUMN policy_scope_key VARCHAR(256) NOT NULL,
    DROP INDEX uk_purchase_limit_policy_active_scope,
    ADD UNIQUE KEY uk_purchase_limit_policy_active_scope_key (tenant_id, event_id, active_scope_key);

ALTER TABLE purchase_limit_policy_audits
    ADD COLUMN policy_scope_key VARCHAR(256) NULL AFTER includes_presale;
-- Do not backfill historical audit rows from the current policy: their former scope is unknown.
