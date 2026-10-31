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
class Track2ListingAnalysisMigrationMySqlTest {
    static final String ANALYSIS_ID = "7a7a7a7a-7a7a-47a7-87a7-7a7a7a7a7a7a";
    private static final LocalDateTime NOW = LocalDateTime.parse("2026-10-30T12:00:00");

    @Test
    void additiveMigrationPreservesEvidenceAndEnforcesScopedContractRows() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource(
                System.getenv("TD_ISSUE32_MYSQL_URL"), System.getenv("TD_ISSUE32_MYSQL_USER"),
                System.getenv("TD_ISSUE32_MYSQL_PASSWORD"));
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        int priorReports = count(jdbc, "fan_reports");
        int priorAssets = count(jdbc, "evidence_assets");
        int priorReceived = received(jdbc);
        try (Connection connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("docs/database/track2-listing-analysis-migration.sql"));
        }
        assertThat(count(jdbc, "fan_reports")).isEqualTo(priorReports);
        assertThat(count(jdbc, "evidence_assets")).isEqualTo(priorAssets);
        assertThat(received(jdbc)).isEqualTo(priorReceived);
        assertThat(count(jdbc, "listing_context_analyses")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.referential_constraints "
                + "WHERE constraint_schema=DATABASE() AND constraint_name IN ('fk_listing_analyses_report',"
                + "'fk_listing_analyses_asset','fk_listing_analyses_actor','fk_listing_observations_analysis')",
                Integer.class)).isEqualTo(4);

        Map<String, Object> scope = jdbc.queryForMap("SELECT r.id report_id, r.tenant_id tenant_id, r.event_id event_id, "
                + "m.id membership_id FROM fan_reports r JOIN memberships m ON m.tenant_id=r.tenant_id "
                + "ORDER BY r.received_at, r.id, m.id LIMIT 1");
        String report = (String) scope.get("report_id");
        String tenant = (String) scope.get("tenant_id");
        String event = (String) scope.get("event_id");
        String member = (String) scope.get("membership_id");
        String asset = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO evidence_assets (id,fan_report_id,object_key,media_type,byte_size,sha256,"
                        + "object_version,storage_status,created_at,retention_until) VALUES (?,?,?,?,?,?,?,?,?,?)",
                asset, report, "fan-reports/" + report + "/" + asset, "image/png", 4, "a".repeat(64), "etag",
                "STORED", NOW, NOW.plusDays(90));

        String active = UUID.randomUUID().toString();
        insert(jdbc, active, tenant, event, report, asset, member, asset, "IN_PROGRESS", null, null);
        assertThatThrownBy(() -> insert(jdbc, UUID.randomUUID().toString(), tenant, event, report, asset, member,
                asset, "IN_PROGRESS", null, null)).isInstanceOf(DataIntegrityViolationException.class);
        assertCheck(() -> insert(jdbc, UUID.randomUUID().toString(), tenant, event, report, asset, member,
                null, "IN_PROGRESS", null, null));
        assertThat(jdbc.update("UPDATE listing_context_analyses SET execution_status='ANALYZER_FAILED', "
                + "analysis_status='FAILED', error_code='ANALYZER_TIMEOUT', active_evidence_id=NULL, "
                + "candidate_match_status='NOT_LINKED', candidate_match_reason='ANALYSIS_NOT_SUCCEEDED', "
                + "completed_at=? WHERE id=?", NOW, active)).isEqualTo(1);

        insert(jdbc, ANALYSIS_ID, tenant, event, report, asset, member, null, "SUCCEEDED", "COMPLETED",
                "PERFORMANCE_REF_UNVERIFIED");
        observation(jdbc, ANALYSIS_ID, "saleText", "FOUND", "양도합니다", null, null, "양도합니다", null, null, null);
        observation(jdbc, ANALYSIS_ID, "askingPrice", "FOUND", null, "45만원", "450000", "45만원", null, null, null);
        observation(jdbc, ANALYSIS_ID, "zone", "AMBIGUOUS", null, null, null, "A 또는 B", null, "A", "B");
        observation(jdbc, ANALYSIS_ID, "seat", "UNKNOWN", null, null, null, null, "ABSENT", null, null);

        assertThatThrownBy(() -> insert(jdbc, UUID.randomUUID().toString(), tenant, UUID.randomUUID().toString(),
                report, asset, member, null, "SUCCEEDED", "COMPLETED", "PERFORMANCE_REF_UNVERIFIED"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(jdbc, UUID.randomUUID().toString(), tenant, event, report,
                UUID.randomUUID().toString(), member, null, "SUCCEEDED", "COMPLETED", "PERFORMANCE_REF_UNVERIFIED"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertCheck(() -> insert(jdbc, UUID.randomUUID().toString(), tenant, event, report, asset, member, null,
                "SUCCEEDED", "FAILED", "PERFORMANCE_REF_UNVERIFIED"));
        assertCheck(() -> insert(jdbc, UUID.randomUUID().toString(), tenant, event, report, asset, member, null,
                "SUCCEEDED", "COMPLETED", "MATCH_CANDIDATE"));
        assertCheck(() -> insert(jdbc, UUID.randomUUID().toString(), tenant, event, report, asset, member, null,
                "SUCCEEDED", null, "PERFORMANCE_REF_UNVERIFIED"));
        assertCheck(() -> observation(jdbc, ANALYSIS_ID, "eventName", "FOUND", "가상 콘서트", null, null, null,
                null, null, null));
        assertCheck(() -> observation(jdbc, ANALYSIS_ID, "row", "UNKNOWN", null, null, null, null, null, null, null));
        assertCheck(() -> observation(jdbc, ANALYSIS_ID, "platformDisplayName", "AMBIGUOUS", null, null, null, "A",
                null, "A", null));
        assertThatThrownBy(() -> observation(jdbc, ANALYSIS_ID, "seat", "UNKNOWN", null, null, null, null,
                "ABSENT", null, null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count(jdbc, "listing_context_observations")).isEqualTo(4);
    }

    private static void insert(JdbcTemplate jdbc, String id, String tenant, String event, String report,
            String asset, String member, String activeEvidence, String execution, String status, String reason) {
        boolean succeeded = "SUCCEEDED".equals(execution);
        jdbc.update("INSERT INTO listing_context_analyses (id,tenant_id,event_id,fan_report_id,evidence_id,"
                        + "created_by_membership_id,idempotency_key_digest,request_id,active_evidence_id,"
                        + "object_key_sha256,object_marker,expected_sha256,verified_sha256,expected_byte_size,"
                        + "verified_byte_size,media_type,execution_status,analysis_status,model_name,model_version,"
                        + "analyzed_at,review_required,response_sha256,candidate_match_status,candidate_match_reason,"
                        + "created_at,lease_expires_at,completed_at,row_version) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)",
                id, tenant, event, report, asset, member,
                UUID.randomUUID().toString().replace("-", "") + "0".repeat(32), UUID.randomUUID().toString(),
                activeEvidence, "c".repeat(64), "etag", "a".repeat(64), succeeded ? "a".repeat(64) : null, 4,
                succeeded ? 4L : null, "image/png", execution, status, succeeded ? "fake-test-provider" : null,
                succeeded ? "test-v1" : null, succeeded ? NOW : null, succeeded ? Boolean.TRUE : null,
                succeeded ? "d".repeat(64) : null, reason == null ? null : "NOT_LINKED", reason, NOW,
                NOW.plusMinutes(5), succeeded ? NOW : null);
    }

    private static void observation(JdbcTemplate jdbc, String analysis, String field, String state, String value,
            String raw, String amount, String quote, String reason, String first, String second) {
        jdbc.update("INSERT INTO listing_context_observations (id,analysis_id,field_name,state,value_text,price_raw,"
                        + "price_amount,evidence_quote,unknown_reason,candidate_1,candidate_2,candidate_3) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,NULL)",
                UUID.randomUUID().toString(), analysis, field, state, value, raw, amount, quote, reason, first, second);
    }

    private static void assertCheck(Runnable insert) {
        assertThatThrownBy(insert::run).isInstanceOfSatisfying(DataAccessException.class, error ->
                assertThat(((SQLException) error.getRootCause()).getErrorCode()).isEqualTo(3819));
    }

    private static int received(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM fan_reports WHERE intake_status='RECEIVED'", Integer.class);
    }

    private static int count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
