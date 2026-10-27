package com.ticket.defender_core.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Uses only the disposable CI schema after the W02 migration test, never a developer database. */
@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MYSQL_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:13307/td_issue32.*")
class CaseMigrationMySqlTest {
    private static final String TENANT = "11111111-1111-4111-8111-111111111111";
    private static final String OTHER_TENANT = "22222222-2222-4222-8222-222222222222";
    private static final String EVENT = "33333333-3333-4333-8333-333333333333";
    private static final String OTHER_EVENT = "44444444-4444-4444-8444-444444444444";

    @Test
    void additiveMigrationPreservesRowsAndEnforcesScopedReferences() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource(
                System.getenv("TD_ISSUE32_MYSQL_URL"), System.getenv("TD_ISSUE32_MYSQL_USER"),
                System.getenv("TD_ISSUE32_MYSQL_PASSWORD"));
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        int reservations = count(jdbc, "reservation");
        int audits = count(jdbc, "ticket_audit");
        int reports = count(jdbc, "fan_reports");
        assertThat(reports).isGreaterThan(0);

        // The first test initializes the pre-W04 schema with JPA. Remove only its empty W04 tables.
        for (String table : new String[]{"evidence_verification_records", "report_versions", "evidence_snapshots",
                "case_idempotency_records", "case_activities",
                "case_source_references", "cases"}) {
            int exists = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema=DATABASE() AND table_name=?", Integer.class, table);
            if (exists != 0 && count(jdbc, table) != 0)
                throw new IllegalStateException("W04 migration test requires empty generated Case tables");
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
        // The preceding W02 test bootstraps older tables from JPA. Match the UUID
        // charset/collation installed by the already-applied admin and Track 1 SQL.
        jdbc.execute("ALTER TABLE memberships MODIFY id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, "
                + "MODIFY tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL");
        jdbc.execute("ALTER TABLE macro_audit_evidence "
                + "MODIFY tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL, "
                + "MODIFY internal_event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL");
        try (Connection connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("docs/database/case-foundation-migration.sql"));
        }
        assertThat(count(jdbc, "reservation")).isEqualTo(reservations);
        assertThat(count(jdbc, "ticket_audit")).isEqualTo(audits);
        assertThat(count(jdbc, "fan_reports")).isEqualTo(reports);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.referential_constraints "
                + "WHERE constraint_schema=DATABASE() AND constraint_name IN "
                + "('fk_cases_event','fk_case_sources_case','fk_case_sources_report',"
                + "'fk_case_sources_asset','fk_case_sources_graph','fk_case_activities_case',"
                + "'fk_case_idempotency_result')", Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics "
                + "WHERE table_schema=DATABASE() AND table_name='case_idempotency_records' "
                + "AND index_name='uk_case_idempotency_scope_key'", Integer.class)).isEqualTo(5);

        String member = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO memberships (id,tenant_id,subject,status) VALUES (?,?,?,?)",
                member, TENANT, "case-test-" + member, "ACTIVE");
        String caseId = UUID.randomUUID().toString();
        assertThatThrownBy(() -> insertCase(jdbc, caseId, TENANT, OTHER_EVENT, member))
                .isInstanceOf(DataIntegrityViolationException.class);
        insertCase(jdbc, caseId, TENANT, EVENT, member);
        String reportId = jdbc.queryForObject("SELECT id FROM fan_reports WHERE receipt_number='FR-synthetic-1'",
                String.class);
        String digest = "f".repeat(64);
        insertReportSource(jdbc, caseId, TENANT, EVENT, reportId, digest);
        assertThatThrownBy(() -> insertReportSource(jdbc, caseId, TENANT, EVENT, reportId, digest))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertReportSource(jdbc, caseId, OTHER_TENANT, OTHER_EVENT,
                reportId, "a".repeat(64))).isInstanceOf(DataIntegrityViolationException.class);
    }

    private static int count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static void insertCase(JdbcTemplate jdbc, String id, String tenant, String event, String member) {
        jdbc.update("INSERT INTO cases (id,tenant_id,event_id,created_by_membership_id,case_type,status,"
                        + "case_version,evidence_revision,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                id, tenant, event, member, "FAN_REPORT", "OPEN", 0, 1,
                LocalDateTime.parse("2026-10-20T12:00:00"), LocalDateTime.parse("2026-10-20T12:00:00"));
    }

    private static void insertReportSource(JdbcTemplate jdbc, String caseId, String tenant,
            String event, String reportId, String digest) {
        jdbc.update("INSERT INTO case_source_references (id,case_id,tenant_id,event_id,source_type,"
                        + "source_key_digest,fan_report_id,linked_at) VALUES (?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), caseId, tenant, event, "FAN_REPORT", digest,
                reportId, LocalDateTime.parse("2026-10-20T12:00:00"));
    }
}
