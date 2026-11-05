package com.ticket.defender_core.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Applies the W10 additive DDL only to the disposable CI MySQL schema. */
@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MYSQL_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:13307/td_issue32.*")
class CaseW10Track2CandidateSourceMigrationMySqlTest {
    @Test
    void additiveMigrationPreservesExistingRowsAndRejectsIncompleteCandidateBinding() throws Exception {
        DriverManagerDataSource source = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        int sourcesBefore = count(jdbc, "case_source_references");
        int snapshotsBefore = count(jdbc, "evidence_snapshots");
        String caseId = jdbc.queryForObject("SELECT id FROM cases ORDER BY created_at, id LIMIT 1", String.class);
        assertThat(caseId).isNotBlank();
        try (Connection connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("docs/database/case-w10-track2-candidate-source-migration.sql"));
        }

        assertThat(count(jdbc, "case_source_references")).isEqualTo(sourcesBefore);
        assertThat(count(jdbc, "evidence_snapshots")).isEqualTo(snapshotsBefore);
        assertThat(constraint(jdbc, "fk_case_sources_candidate_run")).isEqualTo(1);
        assertThat(index(jdbc, "case_source_references", "idx_case_sources_candidate_run")).isEqualTo(3);
        assertThat(check(jdbc, "case_source_references", "chk_case_source_shape")).isEqualTo(1);
        assertThat(check(jdbc, "evidence_snapshots", "chk_evidence_snapshots_schema")).isEqualTo(1);

        assertCheck(() -> jdbc.update("INSERT INTO case_source_references (id,case_id,tenant_id,event_id,"
                        + "source_type,source_key_digest,linked_at) "
                        + "SELECT ?,case_id,tenant_id,event_id,'LISTING_CANDIDATE_MATCH',?,? "
                        + "FROM (SELECT id case_id,tenant_id,event_id FROM cases WHERE id=?) scope",
                UUID.randomUUID().toString(), "f".repeat(64), LocalDateTime.parse("2026-11-05T12:00:00"), caseId));
        assertCheck(() -> jdbc.update("UPDATE evidence_snapshots SET manifest_schema_version=3 "
                + "WHERE id=(SELECT id FROM (SELECT id FROM evidence_snapshots ORDER BY captured_at, id LIMIT 1) snapshot)"));
    }

    private static DriverManagerDataSource dataSource() {
        DriverManagerDataSource source = new DriverManagerDataSource(System.getenv("TD_ISSUE32_MYSQL_URL"),
                System.getenv("TD_ISSUE32_MYSQL_USER"), System.getenv("TD_ISSUE32_MYSQL_PASSWORD"));
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        return source;
    }

    private static int count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static int constraint(JdbcTemplate jdbc, String name) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.referential_constraints "
                + "WHERE constraint_schema=DATABASE() AND constraint_name=?", Integer.class, name);
    }

    private static int index(JdbcTemplate jdbc, String table, String name) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics "
                + "WHERE table_schema=DATABASE() AND table_name=? AND index_name=?", Integer.class, table, name);
    }

    private static int check(JdbcTemplate jdbc, String table, String name) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints "
                + "WHERE constraint_schema=DATABASE() AND table_name=? AND constraint_name=? AND constraint_type='CHECK'",
                Integer.class, table, name);
    }

    private static void assertCheck(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DataAccessException.class, error -> {
            SQLException sql = (SQLException) error.getMostSpecificCause();
            assertThat(sql.getErrorCode()).isEqualTo(3819);
        });
    }
}
