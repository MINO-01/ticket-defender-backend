-- Additive MySQL 8.0 migration for Issue #79 policy semantics.
-- Run only against an isolated approved schema. Existing ACTIVE policies remain active with NULL semantics;
-- they are POLICY_NOT_CONFIRMED only for the new purchase-limit evaluation path.
ALTER TABLE purchase_limit_policies
    ADD COLUMN limit_subject VARCHAR(16) NULL AFTER verification_basis,
    ADD COLUMN application_scope VARCHAR(16) NULL AFTER limit_subject,
    ADD COLUMN sales_phase_ref VARCHAR(128) NULL AFTER application_scope,
    ADD COLUMN cancellation_refund_rule VARCHAR(16) NULL AFTER sales_phase_ref,
    ADD COLUMN includes_presale BOOLEAN NULL AFTER cancellation_refund_rule;

ALTER TABLE purchase_limit_policy_audits
    ADD COLUMN limit_subject VARCHAR(16) NULL AFTER verification_basis,
    ADD COLUMN application_scope VARCHAR(16) NULL AFTER limit_subject,
    ADD COLUMN sales_phase_ref VARCHAR(128) NULL AFTER application_scope,
    ADD COLUMN cancellation_refund_rule VARCHAR(16) NULL AFTER sales_phase_ref,
    ADD COLUMN includes_presale BOOLEAN NULL AFTER cancellation_refund_rule;
