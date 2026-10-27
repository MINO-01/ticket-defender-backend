package com.ticket.defender_core.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MYSQL_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:13307/td_issue32.*")
class CaseW06MigrationMySqlTest {
    static final String RECORD_ID = "88888888-8888-4888-8888-888888888888";

    @Test
    void additiveMigrationPreservesRowsAndEnforcesScopedVerificationReferences() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource(
                System.getenv("TD_ISSUE32_MYSQL_URL"), System.getenv("TD_ISSUE32_MYSQL_USER"),
                System.getenv("TD_ISSUE32_MYSQL_PASSWORD"));
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        int priorCases = count(jdbc, "cases");
        int priorReports = count(jdbc, "fan_reports");
        int priorAssets = count(jdbc, "evidence_assets");
        int priorSnapshots = count(jdbc, "evidence_snapshots");
        int priorDrafts = count(jdbc, "report_versions");
        try (Connection connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("docs/database/case-w06-migration.sql"));
        }
        assertThat(count(jdbc, "cases")).isEqualTo(priorCases);
        assertThat(count(jdbc, "fan_reports")).isEqualTo(priorReports);
        assertThat(count(jdbc, "evidence_assets")).isEqualTo(priorAssets);
        assertThat(count(jdbc, "evidence_snapshots")).isEqualTo(priorSnapshots);
        assertThat(count(jdbc, "report_versions")).isEqualTo(priorDrafts);
        assertThat(count(jdbc, "evidence_verification_records")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.referential_constraints "
                + "WHERE constraint_schema=DATABASE() AND constraint_name IN "
                + "('fk_evidence_verifications_snapshot','fk_evidence_verifications_source',"
                + "'fk_evidence_verifications_report','fk_evidence_verifications_asset',"
                + "'fk_evidence_verifications_actor')", Integer.class)).isEqualTo(5);

        String caseId = jdbc.queryForObject("SELECT id FROM cases ORDER BY created_at LIMIT 1", String.class);
        String tenant = jdbc.queryForObject("SELECT tenant_id FROM cases WHERE id=?", String.class, caseId);
        String event = jdbc.queryForObject("SELECT event_id FROM cases WHERE id=?", String.class, caseId);
        String actor = jdbc.queryForObject("SELECT created_by_membership_id FROM cases WHERE id=?",
                String.class, caseId);
        String snapshot = jdbc.queryForObject("SELECT id FROM evidence_snapshots WHERE case_id=? LIMIT 1",
                String.class, caseId);
        String report = jdbc.queryForObject("SELECT id FROM fan_reports WHERE tenant_id=? AND event_id=? LIMIT 1",
                String.class, tenant, event);
        String asset = UUID.randomUUID().toString();
        String sourceId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.parse("2026-10-20T12:00:00");
        jdbc.update("INSERT INTO evidence_assets (id,fan_report_id,object_key,media_type,byte_size,sha256,"
                        + "object_version,storage_status,created_at,retention_until) VALUES (?,?,?,?,?,?,?,?,?,?)",
                asset, report, "fan-reports/" + report + "/" + asset, "image/png", 4,
                "a".repeat(64), "etag", "STORED", now, now.plusDays(90));
        jdbc.update("INSERT INTO case_source_references (id,case_id,tenant_id,event_id,source_type,"
                        + "source_key_digest,fan_report_id,evidence_id,object_marker,linked_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?)",
                sourceId, caseId, tenant, event, "EVIDENCE_ASSET", "9".repeat(64), report,
                asset, "etag", now);
        insert(jdbc, RECORD_ID, tenant, event, caseId, snapshot, sourceId, report, asset, actor,
                "MATCH");
        assertThat(count(jdbc, "evidence_verification_records")).isEqualTo(1);
        assertThatThrownBy(() -> insert(jdbc, UUID.randomUUID().toString(), tenant,
                UUID.randomUUID().toString(), caseId, snapshot, sourceId, report, asset, actor, "MATCH"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(jdbc, UUID.randomUUID().toString(), tenant,
                event, caseId, snapshot, sourceId, report, UUID.randomUUID().toString(), actor, "MATCH"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(jdbc, UUID.randomUUID().toString(), tenant,
                event, caseId, snapshot, sourceId, report, asset, actor, "APPROVED"))
                .isInstanceOfSatisfying(DataAccessException.class, error -> assertThat(
                        ((java.sql.SQLException) error.getRootCause()).getErrorCode()).isEqualTo(3819));
    }

    private static void insert(JdbcTemplate jdbc, String id, String tenant, String event, String caseId,
            String snapshot, String source, String report, String asset, String actor, String status) {
        LocalDateTime now = LocalDateTime.parse("2026-10-20T12:00:00");
        jdbc.update("INSERT INTO evidence_verification_records (id,tenant_id,event_id,case_id,"
                        + "evidence_snapshot_id,manifest_sha256,evidence_revision,source_reference_id,"
                        + "fan_report_id,evidence_id,object_key_sha256,observed_marker,marker_kind,"
                        + "expected_sha256,verified_sha256,expected_byte_size,verified_byte_size,media_type,"
                        + "retention_until,verification_status,preservation_status,verification_method,"
                        + "created_by_membership_id,verified_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, tenant, event, caseId, snapshot, "b".repeat(64), 1, source, report, asset,
                "c".repeat(64), "etag", "ETAG", "a".repeat(64), "a".repeat(64), 4, 4,
                "image/png", now.plusDays(90), status, "UNPROTECTED", "STREAMED_SHA256", actor, now);
    }

    private static int count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
