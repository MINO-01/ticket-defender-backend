-- READ ONLY. Run only after a separately approved baseline application.
-- Expected final inventory: 85 base tables, including the two preserved legacy tables.
-- Compare this database name and version with the approved preflight result before accepting output.

SELECT DATABASE() AS selected_schema, VERSION() AS mysql_version;
SELECT COUNT(*) AS base_table_count
FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE';
SELECT expected.table_name AS missing_table
FROM (
SELECT 'approval_audit_events' AS table_name
UNION ALL
SELECT 'approval_decisions' AS table_name
UNION ALL
SELECT 'approval_requests' AS table_name
UNION ALL
SELECT 'case_activities' AS table_name
UNION ALL
SELECT 'case_idempotency_records' AS table_name
UNION ALL
SELECT 'case_source_references' AS table_name
UNION ALL
SELECT 'cases' AS table_name
UNION ALL
SELECT 'events' AS table_name
UNION ALL
SELECT 'evidence_access_audit_events' AS table_name
UNION ALL
SELECT 'evidence_assets' AS table_name
UNION ALL
SELECT 'evidence_preservation_attempts' AS table_name
UNION ALL
SELECT 'evidence_preservation_policies' AS table_name
UNION ALL
SELECT 'evidence_preservation_records' AS table_name
UNION ALL
SELECT 'evidence_snapshots' AS table_name
UNION ALL
SELECT 'evidence_verification_records' AS table_name
UNION ALL
SELECT 'fan_report_performance_links' AS table_name
UNION ALL
SELECT 'fan_reports' AS table_name
UNION ALL
SELECT 'issuance_holds' AS table_name
UNION ALL
SELECT 'listing_candidate_match_runs' AS table_name
UNION ALL
SELECT 'listing_context_analyses' AS table_name
UNION ALL
SELECT 'listing_context_observations' AS table_name
UNION ALL
SELECT 'macro_audit_evidence' AS table_name
UNION ALL
SELECT 'membership_events' AS table_name
UNION ALL
SELECT 'membership_roles' AS table_name
UNION ALL
SELECT 'memberships' AS table_name
UNION ALL
SELECT 'official_pdf_artifacts' AS table_name
UNION ALL
SELECT 'official_pdf_audit_events' AS table_name
UNION ALL
SELECT 'official_pdf_issuance_attempts' AS table_name
UNION ALL
SELECT 'official_pdf_issuances' AS table_name
UNION ALL
SELECT 'official_pdf_synthetic_tenants' AS table_name
UNION ALL
SELECT 'performance_link_audits' AS table_name
UNION ALL
SELECT 'performance_verification_audits' AS table_name
UNION ALL
SELECT 'public_request_quota' AS table_name
UNION ALL
SELECT 'purchase_limit_policies' AS table_name
UNION ALL
SELECT 'purchase_limit_policy_audits' AS table_name
UNION ALL
SELECT 'report_versions' AS table_name
UNION ALL
SELECT 'reservation' AS table_name
UNION ALL
SELECT 'reservation_candidates' AS table_name
UNION ALL
SELECT 'reservation_data_sources' AS table_name
UNION ALL
SELECT 'reservation_import_batches' AS table_name
UNION ALL
SELECT 'reservation_import_errors' AS table_name
UNION ALL
SELECT 'review_receipts' AS table_name
UNION ALL
SELECT 'service_tenant_bindings' AS table_name
UNION ALL
SELECT 'synthetic_reservation_import_tenants' AS table_name
UNION ALL
SELECT 'td_bulk_import_errors' AS table_name
UNION ALL
SELECT 'td_bulk_import_jobs' AS table_name
UNION ALL
SELECT 'td_bulk_import_scopes' AS table_name
UNION ALL
SELECT 'td_bulk_import_staging_rows' AS table_name
UNION ALL
SELECT 'td_bulk_import_upload_deletions' AS table_name
UNION ALL
SELECT 'td_bulk_ledger_candidate_imports' AS table_name
UNION ALL
SELECT 'td_bulk_ledger_change_events' AS table_name
UNION ALL
SELECT 'td_bulk_ledger_publication_heads' AS table_name
UNION ALL
SELECT 'td_bulk_ledger_publication_history' AS table_name
UNION ALL
SELECT 'td_bulk_ledger_publication_outbox' AS table_name
UNION ALL
SELECT 'td_bulk_ledger_publications' AS table_name
UNION ALL
SELECT 'td_bulk_ledger_ticket_versions' AS table_name
UNION ALL
SELECT 'td_bulk_ledger_track1_analysis_jobs' AS table_name
UNION ALL
SELECT 'td_bulk_ledger_track1_bulk_clusters' AS table_name
UNION ALL
SELECT 'td_bulk_ledger_track1_bulk_jobs' AS table_name
UNION ALL
SELECT 'td_bulk_ledger_track1_bulk_limit_evaluations' AS table_name
UNION ALL
SELECT 'td_bulk_ledger_track1_bulk_limit_finding_reservations' AS table_name
UNION ALL
SELECT 'td_bulk_ledger_track1_bulk_limit_findings' AS table_name
UNION ALL
SELECT 'td_bulk_ledger_track1_bulk_members' AS table_name
UNION ALL
SELECT 'td_bulk_ledger_track1_bulk_shared_signal_observations' AS table_name
UNION ALL
SELECT 'td_bulk_ledger_track1_policy_snapshots' AS table_name
UNION ALL
SELECT 'td_bulk_ledger_track1_ticket_signals' AS table_name
UNION ALL
SELECT 'td_bulk_ledger_versions' AS table_name
UNION ALL
SELECT 'td_multi_event_investigation_candidates' AS table_name
UNION ALL
SELECT 'td_multi_event_investigation_members' AS table_name
UNION ALL
SELECT 'td_multi_event_investigation_relationship_members' AS table_name
UNION ALL
SELECT 'td_multi_event_investigation_relationship_sources' AS table_name
UNION ALL
SELECT 'td_multi_event_investigation_relationships' AS table_name
UNION ALL
SELECT 'td_multi_event_investigation_runs' AS table_name
UNION ALL
SELECT 'td_multi_event_investigation_scopes' AS table_name
UNION ALL
SELECT 'tenants' AS table_name
UNION ALL
SELECT 'ticket_audit' AS table_name
UNION ALL
SELECT 'track1_purchase_ledger_batches' AS table_name
UNION ALL
SELECT 'track1_purchase_ledger_errors' AS table_name
UNION ALL
SELECT 'track1_purchase_ledger_rows' AS table_name
UNION ALL
SELECT 'track1_purchase_limit_finding_reservations' AS table_name
UNION ALL
SELECT 'track1_purchase_limit_findings' AS table_name
UNION ALL
SELECT 'track1_purchase_limit_run_clusters' AS table_name
UNION ALL
SELECT 'track1_purchase_limit_runs' AS table_name
UNION ALL
SELECT 'track1_purchase_limit_shared_signal_observations' AS table_name
UNION ALL
SELECT 'verified_performances' AS table_name
) AS expected
LEFT JOIN information_schema.tables actual
  ON actual.table_schema = DATABASE()
 AND actual.table_type = 'BASE TABLE'
 AND actual.table_name = expected.table_name
WHERE actual.table_name IS NULL
ORDER BY expected.table_name;
SELECT actual.table_name AS unexpected_base_table
FROM information_schema.tables actual
LEFT JOIN (
SELECT 'approval_audit_events' AS table_name
UNION ALL
SELECT 'approval_decisions' AS table_name
UNION ALL
SELECT 'approval_requests' AS table_name
UNION ALL
SELECT 'case_activities' AS table_name
UNION ALL
SELECT 'case_idempotency_records' AS table_name
UNION ALL
SELECT 'case_source_references' AS table_name
UNION ALL
SELECT 'cases' AS table_name
UNION ALL
SELECT 'events' AS table_name
UNION ALL
SELECT 'evidence_access_audit_events' AS table_name
UNION ALL
SELECT 'evidence_assets' AS table_name
UNION ALL
SELECT 'evidence_preservation_attempts' AS table_name
UNION ALL
SELECT 'evidence_preservation_policies' AS table_name
UNION ALL
SELECT 'evidence_preservation_records' AS table_name
UNION ALL
SELECT 'evidence_snapshots' AS table_name
UNION ALL
SELECT 'evidence_verification_records' AS table_name
UNION ALL
SELECT 'fan_report_performance_links' AS table_name
UNION ALL
SELECT 'fan_reports' AS table_name
UNION ALL
SELECT 'issuance_holds' AS table_name
UNION ALL
SELECT 'listing_candidate_match_runs' AS table_name
UNION ALL
SELECT 'listing_context_analyses' AS table_name
UNION ALL
SELECT 'listing_context_observations' AS table_name
UNION ALL
SELECT 'macro_audit_evidence' AS table_name
UNION ALL
SELECT 'membership_events' AS table_name
UNION ALL
SELECT 'membership_roles' AS table_name
UNION ALL
SELECT 'memberships' AS table_name
UNION ALL
SELECT 'official_pdf_artifacts' AS table_name
UNION ALL
SELECT 'official_pdf_audit_events' AS table_name
UNION ALL
SELECT 'official_pdf_issuance_attempts' AS table_name
UNION ALL
SELECT 'official_pdf_issuances' AS table_name
UNION ALL
SELECT 'official_pdf_synthetic_tenants' AS table_name
UNION ALL
SELECT 'performance_link_audits' AS table_name
UNION ALL
SELECT 'performance_verification_audits' AS table_name
UNION ALL
SELECT 'public_request_quota' AS table_name
UNION ALL
SELECT 'purchase_limit_policies' AS table_name
UNION ALL
SELECT 'purchase_limit_policy_audits' AS table_name
UNION ALL
SELECT 'report_versions' AS table_name
UNION ALL
SELECT 'reservation' AS table_name
UNION ALL
SELECT 'reservation_candidates' AS table_name
UNION ALL
SELECT 'reservation_data_sources' AS table_name
UNION ALL
SELECT 'reservation_import_batches' AS table_name
UNION ALL
SELECT 'reservation_import_errors' AS table_name
UNION ALL
SELECT 'review_receipts' AS table_name
UNION ALL
SELECT 'service_tenant_bindings' AS table_name
UNION ALL
SELECT 'synthetic_reservation_import_tenants' AS table_name
UNION ALL
SELECT 'td_bulk_import_errors' AS table_name
UNION ALL
SELECT 'td_bulk_import_jobs' AS table_name
UNION ALL
SELECT 'td_bulk_import_scopes' AS table_name
UNION ALL
SELECT 'td_bulk_import_staging_rows' AS table_name
UNION ALL
SELECT 'td_bulk_import_upload_deletions' AS table_name
UNION ALL
SELECT 'td_bulk_ledger_candidate_imports' AS table_name
UNION ALL
SELECT 'td_bulk_ledger_change_events' AS table_name
UNION ALL
SELECT 'td_bulk_ledger_publication_heads' AS table_name
UNION ALL
SELECT 'td_bulk_ledger_publication_history' AS table_name
UNION ALL
SELECT 'td_bulk_ledger_publication_outbox' AS table_name
UNION ALL
SELECT 'td_bulk_ledger_publications' AS table_name
UNION ALL
SELECT 'td_bulk_ledger_ticket_versions' AS table_name
UNION ALL
SELECT 'td_bulk_ledger_track1_analysis_jobs' AS table_name
UNION ALL
SELECT 'td_bulk_ledger_track1_bulk_clusters' AS table_name
UNION ALL
SELECT 'td_bulk_ledger_track1_bulk_jobs' AS table_name
UNION ALL
SELECT 'td_bulk_ledger_track1_bulk_limit_evaluations' AS table_name
UNION ALL
SELECT 'td_bulk_ledger_track1_bulk_limit_finding_reservations' AS table_name
UNION ALL
SELECT 'td_bulk_ledger_track1_bulk_limit_findings' AS table_name
UNION ALL
SELECT 'td_bulk_ledger_track1_bulk_members' AS table_name
UNION ALL
SELECT 'td_bulk_ledger_track1_bulk_shared_signal_observations' AS table_name
UNION ALL
SELECT 'td_bulk_ledger_track1_policy_snapshots' AS table_name
UNION ALL
SELECT 'td_bulk_ledger_track1_ticket_signals' AS table_name
UNION ALL
SELECT 'td_bulk_ledger_versions' AS table_name
UNION ALL
SELECT 'td_multi_event_investigation_candidates' AS table_name
UNION ALL
SELECT 'td_multi_event_investigation_members' AS table_name
UNION ALL
SELECT 'td_multi_event_investigation_relationship_members' AS table_name
UNION ALL
SELECT 'td_multi_event_investigation_relationship_sources' AS table_name
UNION ALL
SELECT 'td_multi_event_investigation_relationships' AS table_name
UNION ALL
SELECT 'td_multi_event_investigation_runs' AS table_name
UNION ALL
SELECT 'td_multi_event_investigation_scopes' AS table_name
UNION ALL
SELECT 'tenants' AS table_name
UNION ALL
SELECT 'ticket_audit' AS table_name
UNION ALL
SELECT 'track1_purchase_ledger_batches' AS table_name
UNION ALL
SELECT 'track1_purchase_ledger_errors' AS table_name
UNION ALL
SELECT 'track1_purchase_ledger_rows' AS table_name
UNION ALL
SELECT 'track1_purchase_limit_finding_reservations' AS table_name
UNION ALL
SELECT 'track1_purchase_limit_findings' AS table_name
UNION ALL
SELECT 'track1_purchase_limit_run_clusters' AS table_name
UNION ALL
SELECT 'track1_purchase_limit_runs' AS table_name
UNION ALL
SELECT 'track1_purchase_limit_shared_signal_observations' AS table_name
UNION ALL
SELECT 'verified_performances' AS table_name
) AS expected
  ON expected.table_name = actual.table_name
WHERE actual.table_schema = DATABASE()
  AND actual.table_type = 'BASE TABLE'
  AND expected.table_name IS NULL
ORDER BY actual.table_name;
SELECT table_name, COUNT(*) AS column_count
FROM information_schema.columns
WHERE table_schema = DATABASE()
GROUP BY table_name
ORDER BY table_name;
SELECT table_name, index_name, non_unique,
       GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') AS indexed_columns
FROM information_schema.statistics
WHERE table_schema = DATABASE()
GROUP BY table_name, index_name, non_unique
ORDER BY table_name, index_name;
SELECT table_name, constraint_name, constraint_type
FROM information_schema.table_constraints
WHERE table_schema = DATABASE()
ORDER BY table_name, constraint_type, constraint_name;
SELECT 'reservation' AS table_name, COUNT(*) AS row_count FROM reservation
UNION ALL
SELECT 'ticket_audit', COUNT(*) FROM ticket_audit;
SELECT table_name, column_name, column_type, is_nullable
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND ((table_name = 'reservation' AND column_name IN ('tenant_id','event_id','performance_ref'))
    OR (table_name = 'ticket_audit' AND column_name IN ('report_received_at','evidence_image_urls')))
ORDER BY table_name, column_name;
SELECT table_name, index_name
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND ((table_name = 'ticket_audit' AND index_name IN ('uk_payment_account','uk_reservation_reporter','idx_ticket_audit_reservation_evidence_status'))
    OR (table_name = 'reservation' AND index_name IN ('idx_seat_location','idx_reservation_scope')))
GROUP BY table_name, index_name
ORDER BY table_name, index_name;

-- Full structural catalog for comparison against current-schema-baseline.sql.
-- Result sets expose exact definitions; equality still requires comparing them with the approved target.
SELECT table_name, engine, row_format, table_collation
FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'
ORDER BY table_name;
SELECT table_name, ordinal_position, column_name, column_type, is_nullable,
       column_default, character_set_name, collation_name, extra, generation_expression
FROM information_schema.columns
WHERE table_schema = DATABASE()
ORDER BY table_name, ordinal_position;
SELECT table_name, index_name, non_unique, seq_in_index, column_name,
       expression, sub_part, collation, index_type, is_visible
FROM information_schema.statistics
WHERE table_schema = DATABASE()
ORDER BY table_name, index_name, seq_in_index;
SELECT tc.table_name, tc.constraint_name, tc.constraint_type, tc.enforced,
       kcu.column_name, kcu.ordinal_position, kcu.position_in_unique_constraint,
       kcu.referenced_table_schema, kcu.referenced_table_name, kcu.referenced_column_name
FROM information_schema.table_constraints tc
LEFT JOIN information_schema.key_column_usage kcu
  ON kcu.constraint_schema = tc.constraint_schema
 AND kcu.table_name = tc.table_name
 AND kcu.constraint_name = tc.constraint_name
WHERE tc.constraint_schema = DATABASE()
ORDER BY tc.table_name, tc.constraint_type, tc.constraint_name, kcu.ordinal_position;
SELECT rc.table_name, rc.constraint_name, rc.match_option,
       rc.update_rule, rc.delete_rule
FROM information_schema.referential_constraints rc
WHERE rc.constraint_schema = DATABASE()
ORDER BY rc.table_name, rc.constraint_name;
SELECT tc.table_name, cc.constraint_name, cc.check_clause, tc.enforced
FROM information_schema.check_constraints cc
JOIN information_schema.table_constraints tc
  ON tc.constraint_schema = cc.constraint_schema
 AND tc.constraint_name = cc.constraint_name
 AND tc.constraint_type = 'CHECK'
WHERE cc.constraint_schema = DATABASE()
ORDER BY tc.table_name, cc.constraint_name;
