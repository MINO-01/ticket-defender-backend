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

/** Runs after the W04 migration on the same disposable CI schema. */
@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MYSQL_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:13307/td_issue32.*")
class CaseW05MigrationMySqlTest {
    @Test
    void additiveMigrationPreservesEarlierRowsAndConstrainsBothVersions() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource(
                System.getenv("TD_ISSUE32_MYSQL_URL"), System.getenv("TD_ISSUE32_MYSQL_USER"),
                System.getenv("TD_ISSUE32_MYSQL_PASSWORD"));
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        int priorCases = count(jdbc, "cases");
        int priorReports = count(jdbc, "fan_reports");
        int priorAudits = count(jdbc, "ticket_audit");
        String caseId = jdbc.queryForObject("SELECT id FROM cases ORDER BY created_at LIMIT 1", String.class);
        String tenant = jdbc.queryForObject("SELECT tenant_id FROM cases WHERE id=?", String.class, caseId);
        String event = jdbc.queryForObject("SELECT event_id FROM cases WHERE id=?", String.class, caseId);
        String actor = jdbc.queryForObject("SELECT created_by_membership_id FROM cases WHERE id=?",
                String.class, caseId);
        try (Connection connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("docs/database/case-w05-migration.sql"));
        }
        assertThat(count(jdbc, "cases")).isEqualTo(priorCases);
        assertThat(count(jdbc, "fan_reports")).isEqualTo(priorReports);
        assertThat(count(jdbc, "ticket_audit")).isEqualTo(priorAudits);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.referential_constraints "
                + "WHERE constraint_schema=DATABASE() AND constraint_name IN "
                + "('fk_evidence_snapshots_case','fk_evidence_snapshots_actor',"
                + "'fk_report_versions_case','fk_report_versions_snapshot',"
                + "'fk_report_versions_actor')", Integer.class)).isEqualTo(5);

        String snapshot = UUID.randomUUID().toString();
        insertSnapshot(jdbc, snapshot, tenant, event, caseId, actor, 1);
        assertThatThrownBy(() -> insertSnapshot(jdbc, UUID.randomUUID().toString(), tenant, event,
                caseId, actor, 1)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertSnapshot(jdbc, UUID.randomUUID().toString(), tenant,
                UUID.randomUUID().toString(), caseId, actor, 2))
                .isInstanceOf(DataIntegrityViolationException.class);
        String draft = UUID.randomUUID().toString();
        insertDraft(jdbc, draft, tenant, event, caseId, snapshot, actor, 1);
        assertThatThrownBy(() -> insertDraft(jdbc, UUID.randomUUID().toString(), tenant, event,
                caseId, snapshot, actor, 1)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertDraft(jdbc, UUID.randomUUID().toString(), tenant, event,
                caseId, UUID.randomUUID().toString(), actor, 2))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE report_versions SET status='APPROVED' WHERE id=?", draft))
                .isInstanceOf(DataAccessException.class)
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
    }

    private static void insertSnapshot(JdbcTemplate jdbc, String id, String tenant, String event,
            String caseId, String actor, long revision) {
        jdbc.update("INSERT INTO evidence_snapshots (id,tenant_id,event_id,case_id,evidence_revision,"
                        + "case_version_at_capture,manifest_schema_version,manifest_sha256,manifest_json,"
                        + "source_integrity,created_by_membership_id,captured_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                id, tenant, event, caseId, revision, 1, 1, "a".repeat(64), "{}", "NO_FILE", actor,
                LocalDateTime.parse("2026-10-20T12:00:00"));
    }

    private static void insertDraft(JdbcTemplate jdbc, String id, String tenant, String event,
            String caseId, String snapshot, String actor, long revision) {
        jdbc.update("INSERT INTO report_versions (id,tenant_id,event_id,case_id,evidence_snapshot_id,"
                        + "evidence_revision,revision,version,template_version,content_sha256,content_json,status,"
                        + "created_by_membership_id,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, tenant, event, caseId, snapshot, 1, revision, 0, "draft-input-v1", "b".repeat(64),
                "{}", "DRAFT", actor, LocalDateTime.parse("2026-10-20T12:00:00"));
    }

    private static int count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
