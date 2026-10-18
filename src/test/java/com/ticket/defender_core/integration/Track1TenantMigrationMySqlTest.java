package com.ticket.defender_core.integration;

import com.ticket.defender_core.domain.MacroEvidenceKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.show-sql=false"
})
@EnabledIfEnvironmentVariable(named = "TD_MYSQL_MIGRATION_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:[1-9][0-9]*/.*")
class Track1TenantMigrationMySqlTest {
    private static final UUID TENANT_A = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID EVENT_A = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID EVENT_B = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final String EXTERNAL_EVENT = "shared-event";
    private static final String RESERVATION = "reservation-1";
    private static final String ACCOUNT = "account-1";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TD_MYSQL_MIGRATION_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("TD_MYSQL_MIGRATION_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("TD_MYSQL_MIGRATION_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Test
    void migrationPreservesLegacyRowsAndConstrainsNewScopes() throws Exception {
        jdbc.execute("DROP TABLE service_tenant_bindings");
        jdbc.execute("ALTER TABLE macro_audit_evidence DROP INDEX idx_macro_audit_evidence_scope");
        jdbc.execute("ALTER TABLE macro_audit_evidence DROP COLUMN tenant_id, DROP COLUMN internal_event_id");
        jdbc.execute("DROP TABLE events");
        jdbc.execute("DROP TABLE tenants");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("docs/database/admin-membership-event-migration.sql"));
        }

        jdbc.update("INSERT INTO tenants (id,name,status) VALUES (?,?,?)", TENANT_A.toString(), "고객사 A", "ACTIVE");
        jdbc.update("INSERT INTO tenants (id,name,status) VALUES (?,?,?)", TENANT_B.toString(), "고객사 B", "ACTIVE");
        jdbc.update("INSERT INTO events (id,tenant_id,external_event_ref,name,status) VALUES (?,?,?,?,?)",
                EVENT_A.toString(), TENANT_A.toString(), EXTERNAL_EVENT, "공연 A", "ACTIVE");
        jdbc.update("INSERT INTO events (id,tenant_id,external_event_ref,name,status) VALUES (?,?,?,?,?)",
                EVENT_B.toString(), TENANT_B.toString(), EXTERNAL_EVENT, "공연 B", "ACTIVE");

        long legacyAuditId = auditId();
        String legacyKey = MacroEvidenceKey.create(EXTERNAL_EVENT, RESERVATION, ACCOUNT);
        jdbc.update("""
                INSERT INTO macro_audit_evidence (audit_id,evidence_key,event_id,reservation_no,account_id,
                cluster_id,risk_score,analysis_algorithm,analysis_algorithm_version,analysis_request_id,
                analysis_completed_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, legacyAuditId, legacyKey, EXTERNAL_EVENT, RESERVATION, ACCOUNT,
                "legacy-cluster", 0.81, "LOUVAIN", "1.0.0", "legacy-request",
                LocalDateTime.parse("2026-10-08T10:15:30"));

        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("docs/database/track1-tenant-scoped-evidence-migration.sql"));
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM macro_audit_evidence WHERE audit_id=? "
                + "AND tenant_id IS NULL AND internal_event_id IS NULL AND evidence_key=?", Integer.class,
                legacyAuditId, legacyKey)).isEqualTo(1);

        long firstAuditId = auditId();
        long secondAuditId = auditId();
        String firstKey = MacroEvidenceKey.create(TENANT_A, EVENT_A, RESERVATION, ACCOUNT);
        String secondKey = MacroEvidenceKey.create(TENANT_B, EVENT_B, RESERVATION, ACCOUNT);
        insertEvidence(firstAuditId, firstKey, TENANT_A, EVENT_A);
        insertEvidence(secondAuditId, secondKey, TENANT_B, EVENT_B);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM macro_audit_evidence WHERE tenant_id=? "
                + "AND internal_event_id=?", Integer.class, TENANT_A.toString(), EVENT_A.toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM macro_audit_evidence WHERE tenant_id=? "
                + "AND internal_event_id=?", Integer.class, TENANT_B.toString(), EVENT_B.toString())).isEqualTo(1);

        assertThatThrownBy(() -> insertEvidence(auditId(), firstKey, TENANT_A, EVENT_A))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertEvidence(auditId(),
                MacroEvidenceKey.create(TENANT_A, EVENT_B, "reservation-2", ACCOUNT), TENANT_A, EVENT_B))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertEvidence(auditId(),
                MacroEvidenceKey.create(TENANT_A, EVENT_A, "reservation-3", ACCOUNT), TENANT_A, null))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_macro_audit_evidence_scope");

        jdbc.update("INSERT INTO service_tenant_bindings (id,issuer,client_id,subject,tenant_id,status) "
                + "VALUES (?,?,?,?,?,?)", UUID.randomUUID().toString(), "https://issuer.example.test/service",
                "trusted-ingest", "ingest-a", TENANT_A.toString(), "ACTIVE");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO service_tenant_bindings "
                + "(id,issuer,client_id,subject,tenant_id,status) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID().toString(), "https://issuer.example.test/service",
                "trusted-ingest", "ingest-a", TENANT_B.toString(), "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private long auditId() {
        jdbc.update("INSERT INTO ticket_audit (account_id,reservation_no,evidence_type,status) VALUES (?,?,?,?)",
                ACCOUNT, RESERVATION, "MACRO_GRAPH", "FRAUD_DETECTED");
        return jdbc.queryForObject("SELECT MAX(id) FROM ticket_audit", Long.class);
    }

    private void insertEvidence(long auditId, String evidenceKey, UUID tenantId, UUID internalEventId) {
        jdbc.update("""
                INSERT INTO macro_audit_evidence (audit_id,evidence_key,event_id,reservation_no,account_id,
                cluster_id,risk_score,analysis_algorithm,analysis_algorithm_version,analysis_request_id,
                analysis_completed_at,tenant_id,internal_event_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, auditId, evidenceKey, EXTERNAL_EVENT, RESERVATION, ACCOUNT,
                "cluster-1", 0.81, "LOUVAIN", "1.0.0", "request-1",
                LocalDateTime.parse("2026-10-08T10:15:30"),
                tenantId == null ? null : tenantId.toString(),
                internalEventId == null ? null : internalEventId.toString());
    }
}
