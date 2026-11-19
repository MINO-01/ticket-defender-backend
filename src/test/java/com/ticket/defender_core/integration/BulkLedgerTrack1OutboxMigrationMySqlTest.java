package com.ticket.defender_core.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

/** BL-06 is deliberately applied only after the #81 Track 1 and BL-01~05 chains in disposable MySQL CI. */
@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MYSQL_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/td_issue32_ci.*")
class BulkLedgerTrack1OutboxMigrationMySqlTest {
    @Test
    void appliesOutboxAnalysisConstraintsAfterBothPredecessorChains() throws Exception {
        var source = new DriverManagerDataSource(System.getenv("TD_ISSUE32_MYSQL_URL"), System.getenv("TD_ISSUE32_MYSQL_USER"), System.getenv("TD_ISSUE32_MYSQL_PASSWORD"));
        source.setDriverClassName("com.mysql.cj.jdbc.Driver"); JdbcTemplate jdbc = new JdbcTemplate(source);
        try (Connection connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("docs/database/bulk-ledger-import-bl06-track1-outbox-migration.sql"));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("docs/database/bulk-ledger-track1-bulk-job-migration.sql"));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("docs/database/bulk-ledger-track1-auxiliary-results-migration.sql"));
        }
        assertThat(jdbc.queryForObject("select count(*) from information_schema.table_constraints where table_schema=database() and table_name='td_bulk_ledger_track1_analysis_jobs' and constraint_name='uk_td_bl06_analysis_outbox'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.table_constraints where table_schema=database() and table_name='td_bulk_ledger_track1_ticket_signals' and constraint_name='uk_td_bl06_signal_ticket'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.table_constraints where table_schema=database() and table_name='td_bulk_ledger_track1_bulk_jobs' and constraint_name='uk_bl07_job_outbox'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.table_constraints where table_schema=database() and table_name='td_bulk_ledger_track1_bulk_members' and constraint_name='PRIMARY'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.table_constraints where table_schema=database() and table_name='td_bulk_ledger_track1_policy_snapshots' and constraint_name='PRIMARY'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.table_constraints where table_schema=database() and table_name='td_bulk_ledger_track1_bulk_limit_findings' and constraint_name='PRIMARY'", Integer.class)).isEqualTo(1);
    }
}
