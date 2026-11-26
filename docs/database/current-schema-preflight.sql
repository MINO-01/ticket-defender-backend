-- READ ONLY. Run before any write. Do not paste row values or credentials into reports.
-- STOP unless DATABASE() is the verified synthetic development schema, MySQL is exactly 8.0.46,
-- only the documented legacy tables reservation and ticket_audit are present, both are empty,
-- the old table shapes and indexes match the recorded baseline, and the external backup path,
-- file size, successful exit code, and SHA-256 have been independently verified.
-- Also stop for an unexpected column/index/constraint, an external-interface binding concern,
-- or any difference from the approved target. This query does not verify the backup file itself.
-- BASIC_TARGET_GATE_PASS is necessary but not sufficient: manually compare all following
-- column/index/constraint definitions and independently checked backup/file hashes.
SELECT CASE
    WHEN DATABASE() = 'ticket_defender'
     AND SUBSTRING_INDEX(VERSION(), '-', 1) = '8.0.46'
     AND (SELECT COUNT(*) FROM information_schema.tables
          WHERE table_schema = DATABASE()) = 2
     AND (SELECT COUNT(*) FROM information_schema.tables
          WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'
            AND table_name IN ('reservation', 'ticket_audit')) = 2
     AND (SELECT COUNT(*) FROM information_schema.views
          WHERE table_schema = DATABASE()) = 0
     AND (SELECT COUNT(*) FROM information_schema.triggers
          WHERE trigger_schema = DATABASE()) = 0
     AND (SELECT COUNT(*) FROM information_schema.routines
          WHERE routine_schema = DATABASE()) = 0
     AND (SELECT COUNT(*) FROM information_schema.events
          WHERE event_schema = DATABASE()) = 0
     AND (SELECT COUNT(*) FROM reservation) = 0
     AND (SELECT COUNT(*) FROM ticket_audit) = 0
     AND NOT EXISTS (
          SELECT 1 FROM information_schema.columns
          WHERE table_schema = DATABASE() AND table_name = 'reservation'
            AND column_name IN ('tenant_id', 'event_id', 'performance_ref'))
     AND NOT EXISTS (
          SELECT 1 FROM information_schema.statistics
          WHERE table_schema = DATABASE() AND table_name = 'reservation'
            AND index_name = 'idx_reservation_scope')
     AND NOT EXISTS (
          SELECT 1 FROM information_schema.columns
          WHERE table_schema = DATABASE() AND table_name = 'ticket_audit'
            AND column_name IN ('report_received_at', 'evidence_image_urls'))
     AND NOT EXISTS (
          SELECT 1 FROM information_schema.statistics
          WHERE table_schema = DATABASE() AND table_name = 'ticket_audit'
            AND index_name = 'idx_ticket_audit_reservation_evidence_status')
     AND NOT EXISTS (
          SELECT 1 FROM information_schema.statistics
          WHERE table_schema = DATABASE() AND table_name = 'ticket_audit'
            AND index_name = 'uk_reservation_reporter'
          GROUP BY table_schema, table_name, index_name
          HAVING COUNT(*) <> 2 OR MIN(non_unique) <> 0 OR MAX(non_unique) <> 0
             OR GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
                  <> 'reservation_no,reporter_id')
     AND NOT EXISTS (
          SELECT 1 FROM information_schema.statistics
          WHERE table_schema = DATABASE() AND table_name = 'ticket_audit' AND index_name = 'uk_payment_account'
          GROUP BY table_schema, table_name, index_name
          HAVING COUNT(*) <> 2 OR MIN(non_unique) <> 0 OR MAX(non_unique) <> 0
             OR GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
                  <> 'payment_hash,account_id')
     AND NOT EXISTS (
          SELECT 1 FROM information_schema.statistics
          WHERE table_schema = DATABASE() AND table_name = 'ticket_audit'
            AND index_name = 'idx_ticket_audit_event_reservation_evidence'
          GROUP BY table_schema, table_name, index_name
          HAVING COUNT(*) <> 3 OR MIN(non_unique) <> 1 OR MAX(non_unique) <> 1
             OR GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
                  <> 'event_id,reservation_no,evidence_type')
    THEN 'BASIC_TARGET_GATE_PASS'
    ELSE 'STOP_PREFLIGHT_MISMATCH'
END AS preflight_gate;

SELECT DATABASE() AS selected_schema, VERSION() AS mysql_version, @@version_comment AS server_build;
SELECT table_type, COUNT(*) AS object_count
FROM information_schema.tables
WHERE table_schema = DATABASE()
GROUP BY table_type
ORDER BY table_type;
SELECT table_name, table_type
FROM information_schema.tables
WHERE table_schema = DATABASE()
ORDER BY table_type, table_name;
SELECT 'reservation' AS table_name, COUNT(*) AS row_count FROM reservation
UNION ALL
SELECT 'ticket_audit', COUNT(*) FROM ticket_audit;
SELECT table_name, column_name, column_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name IN ('reservation', 'ticket_audit')
ORDER BY table_name, ordinal_position;
SELECT table_name, index_name, non_unique,
       GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') AS indexed_columns
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name IN ('reservation', 'ticket_audit')
GROUP BY table_name, index_name, non_unique
ORDER BY table_name, index_name;
SELECT table_name, constraint_name, constraint_type
FROM information_schema.table_constraints
WHERE table_schema = DATABASE()
  AND table_name IN ('reservation', 'ticket_audit')
ORDER BY table_name, constraint_type, constraint_name;
SELECT table_name, index_name, non_unique, seq_in_index, column_name,
       expression, sub_part, collation, index_type, is_visible
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name IN ('reservation', 'ticket_audit')
ORDER BY table_name, index_name, seq_in_index;
SHOW CREATE TABLE reservation;
SHOW CREATE TABLE ticket_audit;
