-- Upgrade for the earlier Issue #79 policy table. No policy or audit rows are deleted.
ALTER TABLE purchase_limit_policies ADD COLUMN active_performance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER performance_id;
UPDATE purchase_limit_policies SET active_performance_id = performance_id WHERE status = 'ACTIVE';
ALTER TABLE purchase_limit_policies DROP INDEX uk_purchase_limit_policy_scope,
    ADD UNIQUE KEY uk_purchase_limit_policy_active_scope (tenant_id,event_id,active_performance_id),
    ADD UNIQUE KEY uk_purchase_limit_policy_version_scope (tenant_id,event_id,performance_id,policy_version);
