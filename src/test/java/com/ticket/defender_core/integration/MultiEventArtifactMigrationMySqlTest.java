package com.ticket.defender_core.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

/** Applies the MEI schema chain only after BL-07 in the disposable MySQL 8 CI database. */
@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MYSQL_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/td_issue32_(ci|bl01|minio_ci).*" )
class MultiEventArtifactMigrationMySqlTest {
    private static final String[] MIGRATIONS = {
            "bulk-ledger-score-v2-exp2-signal-migration.sql",
            "multi-event-investigation-migration.sql",
            "multi-event-quantity-snapshot-migration.sql",
            "multi-event-source-binding-migration.sql",
            "multi-event-artifact-job-migration.sql"
    };

    @Test
    void appliesExp2AndMultiEventMigrationsInOrderAndConstrainsArtifactJobs() throws Exception {
        var source = new DriverManagerDataSource(System.getenv("TD_ISSUE32_MYSQL_URL"),
                System.getenv("TD_ISSUE32_MYSQL_USER"), System.getenv("TD_ISSUE32_MYSQL_PASSWORD"));
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        try (Connection connection = source.getConnection()) {
            for (String migration : MIGRATIONS) {
                ScriptUtils.executeSqlScript(connection, new FileSystemResource("docs/database/" + migration));
            }
        }

        assertColumns(jdbc, "td_bulk_ledger_track1_ticket_signals",
                "refund_account_hmac", "phone_hmac", "ip_network_type");
        assertNullableColumn(jdbc, "td_bulk_ledger_track1_ticket_signals", "refund_account_hmac");
        assertNullableColumn(jdbc, "td_bulk_ledger_track1_ticket_signals", "phone_hmac");
        assertNullableColumn(jdbc, "td_bulk_ledger_track1_ticket_signals", "ip_network_type");
        assertColumns(jdbc, "td_multi_event_investigation_runs", "data_job_id", "artifact_id",
                "artifact_object_version", "artifact_sha256", "artifact_record_count", "artifact_cancelled_ticket_count");
        assertCheck(jdbc, "chk_mei_artifact_binding");
        assertCheck(jdbc, "chk_mei_cancelled_artifact_count");
        assertCheck(jdbc, "chk_mei_run_state");
        assertConstraint(jdbc, "td_multi_event_investigation_scopes", "fk_mei_scope_version_identity", "FOREIGN KEY");
        assertConstraint(jdbc, "td_multi_event_investigation_scopes", "fk_mei_scope_policy_snapshot_version", "FOREIGN KEY");
        assertIndex(jdbc, "td_multi_event_investigation_runs", "uk_mei_data_job", true);
        assertIndex(jdbc, "td_multi_event_investigation_runs", "idx_mei_data_claim", false);

        String runCheck = checkClause(jdbc, "chk_mei_run_state").toUpperCase(java.util.Locale.ROOT);
        assertThat(runCheck).contains("DATA_PENDING", "RUNNING", "LEASE_OWNER", "LEASE_UNTIL");
        String artifactCheck = checkClause(jdbc, "chk_mei_artifact_binding").toUpperCase(java.util.Locale.ROOT);
        assertThat(artifactCheck).contains("ARTIFACT_RECORD_COUNT", "400000", "DATA_JOB_ID");
    }

    private static void assertColumns(JdbcTemplate jdbc, String table, String... columns) {
        for (String column : columns) {
            assertThat(jdbc.queryForObject("select count(*) from information_schema.columns "
                    + "where table_schema=database() and table_name=? and column_name=?", Integer.class, table, column))
                    .as(table + "." + column).isEqualTo(1);
        }
    }

    private static void assertNullableColumn(JdbcTemplate jdbc, String table, String column) {
        assertThat(jdbc.queryForObject("select is_nullable from information_schema.columns "
                + "where table_schema=database() and table_name=? and column_name=?", String.class, table, column))
                .isEqualTo("YES");
    }

    private static void assertCheck(JdbcTemplate jdbc, String constraint) {
        assertThat(jdbc.queryForObject("select count(*) from information_schema.table_constraints "
                + "where constraint_schema=database() and constraint_name=? and constraint_type='CHECK'",
                Integer.class, constraint)).isEqualTo(1);
    }

    private static String checkClause(JdbcTemplate jdbc, String constraint) {
        return jdbc.queryForObject("select check_clause from information_schema.check_constraints "
                + "where constraint_schema=database() and constraint_name=?", String.class, constraint);
    }

    private static void assertConstraint(JdbcTemplate jdbc, String table, String constraint, String type) {
        assertThat(jdbc.queryForObject("select count(*) from information_schema.table_constraints "
                + "where constraint_schema=database() and table_name=? and constraint_name=? and constraint_type=?",
                Integer.class, table, constraint, type)).isEqualTo(1);
    }

    private static void assertIndex(JdbcTemplate jdbc, String table, String index, boolean unique) {
        Integer nonUnique = jdbc.queryForObject("select non_unique from information_schema.statistics "
                + "where table_schema=database() and table_name=? and index_name=? limit 1", Integer.class, table, index);
        assertThat(nonUnique).isNotNull();
        assertThat(nonUnique == 0).isEqualTo(unique);
    }
}
