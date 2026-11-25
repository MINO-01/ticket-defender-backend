-- Additive migration for pinning the policy snapshot used by a multi-event run.
-- Apply after multi-event-investigation-migration.sql and bulk-ledger-track1-auxiliary-results-migration.sql.
-- Follow with multi-event-source-binding-migration.sql to bind the snapshot to its exact ledger version.
ALTER TABLE td_multi_event_investigation_scopes
    ADD COLUMN policy_snapshot_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER source_batch_id,
    ADD KEY idx_mei_scope_policy_snapshot (policy_snapshot_id),
    ADD CONSTRAINT fk_mei_scope_policy_snapshot
        FOREIGN KEY (policy_snapshot_id)
        REFERENCES td_bulk_ledger_track1_policy_snapshots (snapshot_id);
