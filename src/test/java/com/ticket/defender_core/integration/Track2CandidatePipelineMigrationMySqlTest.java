package com.ticket.defender_core.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MYSQL_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:13307/td_issue32.*")
class Track2CandidatePipelineMigrationMySqlTest {
    private static final LocalDateTime NOW = LocalDateTime.parse("2026-11-04T12:00:00");

    @Test
    void additiveMigrationKeepsLegacyReservationAndEnforcesSyntheticSnapshotBoundaries() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource(System.getenv("TD_ISSUE32_MYSQL_URL"),
                System.getenv("TD_ISSUE32_MYSQL_USER"), System.getenv("TD_ISSUE32_MYSQL_PASSWORD"));
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        int legacyReservations = jdbc.queryForObject("SELECT COUNT(*) FROM reservation", Integer.class);
        try (Connection connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("docs/database/track2-candidate-pipeline-migration.sql"));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation", Integer.class)).isEqualTo(legacyReservations);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() "
                + "AND table_name IN ('verified_performances','reservation_import_batches','reservation_candidates',"
                + "'reservation_data_sources','listing_candidate_match_runs')", Integer.class)).isEqualTo(5);

        Map<String, Object> scope = jdbc.queryForMap("SELECT r.tenant_id tenant_id, r.event_id event_id, r.id report_id, "
                + "m.id membership_id FROM fan_reports r JOIN memberships m ON m.tenant_id=r.tenant_id "
                + "ORDER BY r.received_at, r.id, m.id LIMIT 1");
        String tenant = (String) scope.get("tenant_id");
        String event = (String) scope.get("event_id");
        String report = (String) scope.get("report_id");
        String membership = (String) scope.get("membership_id");
        String performance = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO verified_performances (id,tenant_id,event_id,performance_ref,verification_basis,"
                        + "source_version,verified_by_membership_id,verified_at,status,revision,row_version) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,0)", performance, tenant, event, "round-candidate-1",
                "synthetic fixture", "performance-v1", membership, NOW, "ACTIVE", 1);
        String batch = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO reservation_import_batches (id,tenant_id,event_id,performance_id,performance_ref,"
                        + "created_by_membership_id,idempotency_key_digest,request_digest,source_version,"
                        + "reference_hmac_key_version,synthetic_isolation_id,complete_snapshot,status,input_row_count,"
                        + "accepted_row_count,rejected_row_count,created_at,completed_at,row_version) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)", batch, tenant, event, performance,
                "round-candidate-1", membership, "a".repeat(64), "b".repeat(64), "source-v1", "v1",
                UUID.randomUUID().toString(), true, "ACCEPTED", 1, 1, 0, NOW, NOW);
        String candidate = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO reservation_candidates (id,batch_id,source_row_index,reservation_ref_hmac,"
                        + "reference_hmac_key_version,zone,row_num,seat_num) VALUES (?,?,?,?,?,?,?,?)", candidate,
                batch, 0, "c".repeat(64), "v1", "VIP", "10", "5");
        jdbc.update("INSERT INTO reservation_data_sources (id,tenant_id,event_id,performance_id,current_batch_id,"
                        + "current_source_version,complete_snapshot,source_revision,updated_at,row_version) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,0)", UUID.randomUUID().toString(), tenant, event, performance,
                batch, "source-v1", true, 1, NOW);

        assertThatThrownBy(() -> jdbc.update("INSERT INTO reservation_candidates (id,batch_id,source_row_index,"
                        + "reservation_ref_hmac,reference_hmac_key_version,zone,row_num,seat_num) VALUES (?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), batch, 1, "c".repeat(64), "v1", "VIP", "10", "6"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertCheck(() -> jdbc.update("INSERT INTO reservation_import_batches (id,tenant_id,event_id,performance_id,"
                        + "performance_ref,created_by_membership_id,idempotency_key_digest,request_digest,source_version,"
                        + "reference_hmac_key_version,synthetic_isolation_id,complete_snapshot,status,input_row_count,"
                        + "accepted_row_count,rejected_row_count,created_at,completed_at,row_version) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)", UUID.randomUUID().toString(), tenant, event,
                performance, "round-candidate-1", membership, "d".repeat(64), "e".repeat(64), "broken", "v1",
                UUID.randomUUID().toString(), false, "ACCEPTED", 1, 1, 0, NOW, NOW));
        assertThat(report).isNotBlank();
    }

    private static void assertCheck(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DataAccessException.class, error ->
                assertThat(((SQLException) error.getRootCause()).getErrorCode()).isEqualTo(3819));
    }
}
