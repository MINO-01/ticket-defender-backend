package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.out.persistence.ReservationRepository;
import com.ticket.defender_core.adapter.out.persistence.TicketAuditRepository;
import com.ticket.defender_core.domain.Reservation;
import com.ticket.defender_core.domain.TicketAudit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs only against a fresh, disposable local database on the dedicated review port. */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create")
@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MYSQL_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:13307/td_issue32.*")
class FanReportMigrationMySqlTest {
    private static final UUID TENANT_A = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID EVENT_A = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID EVENT_B = UUID.fromString("44444444-4444-4444-8444-444444444444");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource datasource;
    @Autowired private ReservationRepository reservations;
    @Autowired private TicketAuditRepository audits;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TD_ISSUE32_MYSQL_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("TD_ISSUE32_MYSQL_USER"));
        registry.add("spring.datasource.password", () ->
                System.getenv().getOrDefault("TD_ISSUE32_MYSQL_PASSWORD", ""));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Test
    void migrationPreservesExistingDataAndConstrainsReportScope() throws Exception {
        jdbc.update("INSERT INTO tenants (id,name,status) VALUES (?,?,?)", TENANT_A.toString(), "Synthetic A", "ACTIVE");
        jdbc.update("INSERT INTO tenants (id,name,status) VALUES (?,?,?)", TENANT_B.toString(), "Synthetic B", "ACTIVE");
        jdbc.update("INSERT INTO events (id,tenant_id,external_event_ref,name,status,public_intake_enabled) "
                + "VALUES (?,?,?,?,?,b'0')", EVENT_A.toString(), TENANT_A.toString(), "same-title", "Event", "ACTIVE");
        jdbc.update("INSERT INTO events (id,tenant_id,external_event_ref,name,status,public_intake_enabled) "
                + "VALUES (?,?,?,?,?,b'0')", EVENT_B.toString(), TENANT_B.toString(), "same-title", "Event", "ACTIVE");
        reservations.saveAndFlush(new Reservation("legacy-reservation", "synthetic-account", "VIP", "1", "1", 1000L));
        audits.saveAndFlush(new TicketAudit("synthetic-account", null, null));
        int oldReservations = jdbc.queryForObject("SELECT COUNT(*) FROM reservation", Integer.class);
        int oldAudits = jdbc.queryForObject("SELECT COUNT(*) FROM ticket_audit", Integer.class);

        // Current JPA generated baseline minus the additions in this migration models the pre-issue schema.
        jdbc.execute("DROP TABLE evidence_verification_records");
        jdbc.execute("DROP TABLE evidence_assets");
        jdbc.execute("DROP TABLE fan_reports");
        jdbc.execute("ALTER TABLE events DROP COLUMN public_intake_enabled");
        jdbc.execute("ALTER TABLE tenants MODIFY id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL");
        jdbc.execute("ALTER TABLE events MODIFY id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, "
                + "MODIFY tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL");
        try (Connection connection = datasource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("docs/database/fan-report-intake-migration.sql"));
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation", Integer.class)).isEqualTo(oldReservations);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_audit", Integer.class)).isEqualTo(oldAudits);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events WHERE public_intake_enabled=b'0'", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics "
                + "WHERE table_schema=DATABASE() AND table_name='fan_reports' "
                + "AND index_name='uk_fan_reports_idempotency'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.referential_constraints "
                + "WHERE constraint_schema=DATABASE() AND constraint_name='fk_fan_reports_event_scope'",
                Integer.class)).isEqualTo(1);

        String reportId = UUID.randomUUID().toString();
        insertReport(reportId, TENANT_A, EVENT_A, "FR-synthetic-1", "a".repeat(64));
        assertThatThrownBy(() -> insertReport(UUID.randomUUID().toString(), TENANT_A, EVENT_B,
                "FR-synthetic-2", "b".repeat(64))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertReport(UUID.randomUUID().toString(), TENANT_A, EVENT_A,
                "FR-synthetic-3", "a".repeat(64))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO evidence_assets "
                        + "(id,fan_report_id,object_key,media_type,byte_size,sha256,storage_status,created_at,retention_until) "
                        + "VALUES (?,?,?,?,?,?,?,?,?)", UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                        "fan-reports/synthetic/missing", "image/png", 4, "a".repeat(64), "PENDING",
                        LocalDateTime.parse("2026-10-20T12:00:00"), LocalDateTime.parse("2027-01-18T12:00:00")))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE fan_reports SET intake_status='ANALYSIS_FAILED' WHERE id=?", reportId);
        assertThat(jdbc.queryForObject("SELECT intake_status FROM fan_reports WHERE id=?", String.class, reportId))
                .isEqualTo("ANALYSIS_FAILED");
    }

    private void insertReport(String id, UUID tenant, UUID event, String receipt, String key) {
        jdbc.update("""
                INSERT INTO fan_reports (id,receipt_number,tenant_id,event_id,submitted_performance_at,
                  report_type,description,received_at,retention_until,intake_status,idempotency_key_digest,request_digest,
                  lookup_secret_digest)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, receipt, tenant.toString(), event.toString(),
                LocalDateTime.parse("2026-11-01T19:30:00"), "OTHER",
                "A synthetic suspected listing description", LocalDateTime.parse("2026-10-20T12:00:00"),
                LocalDateTime.parse("2027-01-18T12:00:00"),
                "RECEIVED", key, "d".repeat(64), "e".repeat(64));
    }
}
