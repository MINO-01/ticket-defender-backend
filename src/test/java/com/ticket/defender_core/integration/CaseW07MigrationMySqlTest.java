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
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MYSQL_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:13307/td_issue32.*")
class CaseW07MigrationMySqlTest {
    static final String POLICY_ID = "a7070707-0707-4707-8707-070707070707";
    static final String POLICY_ROW_ID = "b7070707-0707-4707-8707-070707070707";
    static final String RECORD_ID = "c7070707-0707-4707-8707-070707070707";
    static final String ATTEMPT_ID = "d7070707-0707-4707-8707-070707070707";

    @Test
    void additiveMigrationPreservesPriorRowsAndEnforcesScopedImmutableRecords() throws Exception {
        DriverManagerDataSource source = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        int priorCases = count(jdbc, "cases");
        int priorAssets = count(jdbc, "evidence_assets");
        int priorVerifications = count(jdbc, "evidence_verification_records");
        try (Connection connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("docs/database/case-w07-migration.sql"));
        }
        assertThat(count(jdbc, "cases")).isEqualTo(priorCases);
        assertThat(count(jdbc, "evidence_assets")).isEqualTo(priorAssets);
        assertThat(count(jdbc, "evidence_verification_records")).isEqualTo(priorVerifications);
        assertThat(count(jdbc, "evidence_preservation_policies")).isZero();
        assertThat(count(jdbc, "evidence_preservation_records")).isZero();
        assertThat(count(jdbc, "evidence_preservation_attempts")).isZero();

        Scoped scope = scope(jdbc);
        LocalDateTime now = LocalDateTime.parse("2026-10-27T12:00:00");
        insertPolicy(jdbc, POLICY_ROW_ID, POLICY_ID, 1, scope.tenant(), scope.event(),
                "APPROVED", now.minusDays(1), now.plusDays(30), 10,
                "W07_MYSQL_LOCKED", "GOVERNANCE", "synthetic-test-authority",
                now.minusHours(1), null, now.minusHours(2));
        insertRecord(jdbc, RECORD_ID, scope, POLICY_ID, 1, "locked-version-1",
                now.plusDays(5), now);
        insertAttempt(jdbc, ATTEMPT_ID, scope, POLICY_ID, 1L, RECORD_ID,
                "VERIFIED", "IMMUTABLE_VERIFIED", now);

        assertThat(count(jdbc, "evidence_preservation_policies")).isEqualTo(1);
        assertThat(count(jdbc, "evidence_preservation_records")).isEqualTo(1);
        assertThat(count(jdbc, "evidence_preservation_attempts")).isEqualTo(1);
        assertThatThrownBy(() -> insertRecord(jdbc, UUID.randomUUID().toString(), scope,
                POLICY_ID, 1, "locked-version-1", now.plusDays(5), now))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertPolicy(jdbc, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                1, scope.tenant(), UUID.randomUUID().toString(), "APPROVED", now.minusDays(1),
                now.plusDays(10), 5, "W07_MYSQL_LOCKED", "GOVERNANCE",
                "synthetic-test-authority", now.minusHours(1), null, now.minusHours(2)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertAttempt(jdbc, UUID.randomUUID().toString(), scope,
                POLICY_ID, 1L, null, "VERIFIED", "IMMUTABLE_VERIFIED", now))
                .isInstanceOf(DataAccessException.class)
                .hasRootCauseInstanceOf(java.sql.SQLException.class);

        String otherEvidence = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO evidence_assets (id,fan_report_id,object_key,media_type,byte_size,sha256,"
                        + "object_version,storage_status,created_at,retention_until) VALUES (?,?,?,?,?,?,?,?,?,?)",
                otherEvidence, scope.report(), "fan-reports/" + scope.report() + "/" + otherEvidence,
                "image/png", scope.size(), scope.sha256(), "etag-2", "STORED", now, now.plusDays(30));
        Scoped mismatchedEvidence = new Scoped(scope.tenant(), scope.event(), scope.caseId(),
                scope.snapshot(), scope.source(), scope.report(), otherEvidence, scope.verification(),
                scope.actor(), scope.sha256(), scope.size());
        assertThatThrownBy(() -> insertRecord(jdbc, UUID.randomUUID().toString(), mismatchedEvidence,
                POLICY_ID, 1, "locked-version-2", now.plusDays(5), now))
                .isInstanceOf(DataIntegrityViolationException.class);

        insertPolicy(jdbc, UUID.randomUUID().toString(), POLICY_ID, 2, scope.tenant(), scope.event(),
                "REVOKED", now.minusDays(1), now.plusDays(30), 10,
                "W07_MYSQL_LOCKED", "GOVERNANCE", "synthetic-test-authority",
                now.minusHours(1), now.plusMinutes(1), now.minusHours(2));
        assertThat(jdbc.queryForObject("SELECT preservation_policy_version FROM "
                + "evidence_preservation_records WHERE id=?", Long.class, RECORD_ID)).isEqualTo(1L);
    }

    private static DriverManagerDataSource dataSource() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                System.getenv("TD_ISSUE32_MYSQL_URL"), System.getenv("TD_ISSUE32_MYSQL_USER"),
                System.getenv("TD_ISSUE32_MYSQL_PASSWORD"));
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        return source;
    }

    private static Scoped scope(JdbcTemplate jdbc) {
        String verification = CaseW06MigrationMySqlTest.RECORD_ID;
        return jdbc.queryForObject("SELECT tenant_id,event_id,case_id,evidence_snapshot_id,"
                        + "source_reference_id,fan_report_id,evidence_id,created_by_membership_id,verified_sha256,"
                        + "verified_byte_size FROM evidence_verification_records WHERE id=?",
                (rs, row) -> new Scoped(rs.getString("tenant_id"), rs.getString("event_id"),
                        rs.getString("case_id"), rs.getString("evidence_snapshot_id"),
                        rs.getString("source_reference_id"), rs.getString("fan_report_id"),
                        rs.getString("evidence_id"),
                        verification, rs.getString("created_by_membership_id"),
                        rs.getString("verified_sha256"), rs.getLong("verified_byte_size")), verification);
    }

    private static void insertPolicy(JdbcTemplate jdbc, String id, String policyId, long version,
            String tenant, String event, String status, LocalDateTime from, LocalDateTime until,
            int limit, String profile, String mode, String authority, LocalDateTime approvedAt,
            LocalDateTime revokedAt, LocalDateTime createdAt) {
        jdbc.update("INSERT INTO evidence_preservation_policies (id,policy_id,policy_version,tenant_id,"
                        + "event_id,policy_status,effective_from,effective_until,retention_limit_days,"
                        + "storage_profile,preservation_mode,approval_authority,created_at,approved_at,"
                        + "revoked_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, policyId, version, tenant, event, status, from, until, limit, profile, mode,
                authority, createdAt, approvedAt, revokedAt);
    }

    private static void insertRecord(JdbcTemplate jdbc, String id, Scoped scope, String policyId,
            long policyVersion, String versionId, LocalDateTime retainUntil, LocalDateTime verifiedAt) {
        jdbc.update("INSERT INTO evidence_preservation_records (id,tenant_id,event_id,case_id,"
                        + "evidence_snapshot_id,source_reference_id,evidence_id,verification_record_id,"
                        + "preservation_policy_id,preservation_policy_version,storage_profile,"
                        + "object_key_sha256,actual_version_id,object_sha256,object_size,object_lock_mode,"
                        + "retain_until,preservation_status,verified_at,actor_membership_id) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, scope.tenant(), scope.event(), scope.caseId(), scope.snapshot(), scope.source(),
                scope.evidence(), scope.verification(), policyId, policyVersion, "W07_MYSQL_LOCKED",
                "f".repeat(64), versionId, scope.sha256(), scope.size(), "GOVERNANCE", retainUntil,
                "IMMUTABLE_VERIFIED", verifiedAt, scope.actor());
    }

    private static void insertAttempt(JdbcTemplate jdbc, String id, Scoped scope, String policyId,
            Long version, String recordId, String outcome, String reason, LocalDateTime attemptedAt) {
        jdbc.update("INSERT INTO evidence_preservation_attempts (id,tenant_id,event_id,case_id,"
                        + "evidence_snapshot_id,source_reference_id,evidence_id,verification_record_id,"
                        + "requested_policy_id,resolved_policy_version,preservation_record_id,"
                        + "actor_membership_id,outcome,reason_code,key_digest,request_digest,attempted_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, scope.tenant(), scope.event(), scope.caseId(), scope.snapshot(), scope.source(),
                scope.evidence(), scope.verification(), policyId, version, recordId, scope.actor(), outcome,
                reason, "1".repeat(64), "2".repeat(64), attemptedAt);
    }

    private static int count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private record Scoped(String tenant, String event, String caseId, String snapshot, String source,
                          String report, String evidence, String verification, String actor,
                          String sha256, long size) { }
}
