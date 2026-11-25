-- MEI additive source binding. Apply after the MEI scope, quantity snapshot and BL-07 auxiliary migrations.
-- These composite keys bind every scope to a version from the same tenant/event/performance and bind
-- every pinned policy snapshot to that exact ledger version.
ALTER TABLE td_bulk_ledger_versions
    ADD UNIQUE KEY uk_td_bulk_ledger_version_scope_identity (id, tenant_id, event_id, performance_id);

ALTER TABLE td_bulk_ledger_track1_policy_snapshots
    ADD UNIQUE KEY uk_bl07_policy_snapshot_version_identity (snapshot_id, ledger_version_id);

ALTER TABLE td_multi_event_investigation_scopes
    DROP FOREIGN KEY fk_mei_scope_version,
    DROP FOREIGN KEY fk_mei_scope_policy_snapshot,
    ADD KEY idx_mei_scope_version_identity (ledger_version_id, tenant_id, event_id, performance_id),
    ADD KEY idx_mei_scope_policy_snapshot_version (policy_snapshot_id, ledger_version_id),
    ADD CONSTRAINT fk_mei_scope_version_identity
        FOREIGN KEY (ledger_version_id, tenant_id, event_id, performance_id)
        REFERENCES td_bulk_ledger_versions (id, tenant_id, event_id, performance_id),
    ADD CONSTRAINT fk_mei_scope_policy_snapshot_version
        FOREIGN KEY (policy_snapshot_id, ledger_version_id)
        REFERENCES td_bulk_ledger_track1_policy_snapshots (snapshot_id, ledger_version_id);
