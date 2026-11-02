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

/** Track 2 마이그레이션 다음에 원본 열람 감사 테이블을 추가하고, 기존 데이터 보존과 FK, CHECK, 기본키 제약을 MySQL 8에서 확인합니다. */
@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MYSQL_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:13307/td_issue32.*")
class AdminEvidenceContentMigrationMySqlTest {
    private static final LocalDateTime NOW = LocalDateTime.parse("2026-11-02T12:00:00");

    @Test
    void additiveAuditTableKeepsDataAndRejectsUnscopedOrContradictoryRows() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource(
                System.getenv("TD_ISSUE32_MYSQL_URL"), System.getenv("TD_ISSUE32_MYSQL_USER"),
                System.getenv("TD_ISSUE32_MYSQL_PASSWORD"));
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        int priorReports = count(jdbc, "fan_reports");
        int priorAssets = count(jdbc, "evidence_assets");
        int priorAnalyses = count(jdbc, "listing_context_analyses");
        try (Connection connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("docs/database/admin-evidence-content-audit-migration.sql"));
        }
        assertThat(count(jdbc, "fan_reports")).isEqualTo(priorReports);
        assertThat(count(jdbc, "evidence_assets")).isEqualTo(priorAssets);
        assertThat(count(jdbc, "listing_context_analyses")).isEqualTo(priorAnalyses);
        assertThat(count(jdbc, "evidence_access_audit_events")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.referential_constraints "
                + "WHERE constraint_schema=DATABASE() AND constraint_name IN ('fk_evidence_access_audit_report',"
                + "'fk_evidence_access_audit_asset','fk_evidence_access_audit_actor')", Integer.class)).isEqualTo(3);

        Map<String, Object> scope = jdbc.queryForMap("SELECT a.id asset_id, r.id report_id, r.tenant_id tenant_id, "
                + "r.event_id event_id, m.id membership_id FROM evidence_assets a JOIN fan_reports r "
                + "ON r.id=a.fan_report_id JOIN memberships m ON m.tenant_id=r.tenant_id ORDER BY a.id, m.id LIMIT 1");
        String asset = (String) scope.get("asset_id");
        String report = (String) scope.get("report_id");
        String tenant = (String) scope.get("tenant_id");
        String event = (String) scope.get("event_id");
        String member = (String) scope.get("membership_id");

        String succeeded = UUID.randomUUID().toString();
        insert(jdbc, succeeded, tenant, event, report, asset, member, "SUCCEEDED", "VERIFIED_FOR_RESPONSE");
        insert(jdbc, UUID.randomUUID().toString(), tenant, event, report, null, member, "FAILED",
                "RESOURCE_NOT_FOUND");
        insert(jdbc, UUID.randomUUID().toString(), tenant, event, report, asset, member, "FAILED", "HASH_MISMATCH");
        // 한 요청 ID에는 하나의 결과만 남습니다.
        assertThatThrownBy(() -> insert(jdbc, succeeded, tenant, event, report, asset, member, "FAILED",
                "AUDIT_UNAVAILABLE")).isInstanceOf(DataIntegrityViolationException.class);
        // 이 제보 소속이 아닌 증거, 다른 공연 범위, 다른 고객사 멤버십은 FK로 막습니다.
        assertThatThrownBy(() -> insert(jdbc, UUID.randomUUID().toString(), tenant, event, report,
                UUID.randomUUID().toString(), member, "FAILED", "HASH_MISMATCH"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(jdbc, UUID.randomUUID().toString(), tenant, UUID.randomUUID().toString(),
                report, asset, member, "FAILED", "HASH_MISMATCH")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(jdbc, UUID.randomUUID().toString(), tenant, event, report, asset,
                UUID.randomUUID().toString(), "FAILED", "HASH_MISMATCH"))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 결과와 코드가 맞지 않는 기록은 CHECK로 막습니다.
        assertCheck(() -> insert(jdbc, UUID.randomUUID().toString(), tenant, event, report, asset, member,
                "SUCCEEDED", "HASH_MISMATCH"));
        assertCheck(() -> insert(jdbc, UUID.randomUUID().toString(), tenant, event, report, null, member,
                "SUCCEEDED", "VERIFIED_FOR_RESPONSE"));
        assertCheck(() -> insert(jdbc, UUID.randomUUID().toString(), tenant, event, report, asset, member,
                "FAILED", "VERIFIED_FOR_RESPONSE"));
        assertCheck(() -> insert(jdbc, UUID.randomUUID().toString(), tenant, event, report, asset, member,
                "VIEWED", "VERIFIED_FOR_RESPONSE"));
        assertCheck(() -> insert(jdbc, UUID.randomUUID().toString(), tenant, event, report, asset, member,
                "FAILED", "fan-reports/raw-object-key"));
        assertThat(count(jdbc, "evidence_access_audit_events")).isEqualTo(3);
    }

    private static void insert(JdbcTemplate jdbc, String id, String tenant, String event, String report,
            String asset, String member, String outcome, String code) {
        jdbc.update("INSERT INTO evidence_access_audit_events (id,tenant_id,event_id,fan_report_id,evidence_id,"
                + "actor_membership_id,outcome,detail_code,occurred_at) VALUES (?,?,?,?,?,?,?,?,?)",
                id, tenant, event, report, asset, member, outcome, code, NOW);
    }

    private static void assertCheck(Runnable insert) {
        assertThatThrownBy(insert::run).isInstanceOfSatisfying(DataAccessException.class, error ->
                assertThat(((SQLException) error.getRootCause()).getErrorCode()).isEqualTo(3819));
    }

    private static int count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
