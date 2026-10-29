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
class CaseW08aMigrationMySqlTest {
    static final String REQUEST_ID = "a8080808-0808-4808-8808-080808080808";
    static final String AUDIT_ID = "b8080808-0808-4808-8808-080808080808";
    static final String HOLD_ID = "c8080808-0808-4808-8808-080808080808";
    static final String TEMPLATE_SHA256 = "8".repeat(64);
    static final String CONTENT_SHA256 = "9".repeat(64);

    @Test
    void additiveMigrationPreservesLegacyRowsAndEnforcesApprovalScopeAndActiveSlot() throws Exception {
        DriverManagerDataSource source = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        int priorCases = count(jdbc, "cases");
        int priorReports = count(jdbc, "report_versions");
        String reportId = jdbc.queryForObject("SELECT id FROM report_versions ORDER BY created_at LIMIT 1",
                String.class);
        Scope scope = jdbc.queryForObject("SELECT tenant_id,event_id,case_id,evidence_snapshot_id,"
                        + "created_by_membership_id,revision,content_sha256 FROM report_versions WHERE id=?",
                (rs, row) -> new Scope(rs.getString("tenant_id"), rs.getString("event_id"),
                        rs.getString("case_id"), rs.getString("evidence_snapshot_id"),
                        rs.getString("created_by_membership_id"), rs.getLong("revision"),
                        rs.getString("content_sha256")), reportId);
        try (Connection connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("docs/database/case-w08a-migration.sql"));
        }

        assertThat(count(jdbc, "cases")).isEqualTo(priorCases);
        assertThat(count(jdbc, "report_versions")).isEqualTo(priorReports);
        assertThat(jdbc.queryForObject("SELECT template_sha256 FROM report_versions WHERE id=?",
                String.class, reportId)).isNull();
        assertThat(count(jdbc, "approval_requests")).isZero();
        assertThat(count(jdbc, "approval_audit_events")).isZero();
        assertThat(count(jdbc, "issuance_holds")).isZero();

        LocalDateTime now = LocalDateTime.parse("2026-10-28T12:00:00");
        long before = jdbc.queryForObject("SELECT case_version FROM cases WHERE id=?", Long.class,
                scope.caseId());
        String pinnedReportId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO report_versions (id,tenant_id,event_id,case_id,evidence_snapshot_id,"
                        + "evidence_revision,revision,version,template_version,template_sha256,content_sha256,"
                        + "content_json,status,created_by_membership_id,created_at) "
                        + "SELECT ?,tenant_id,event_id,case_id,evidence_snapshot_id,evidence_revision,"
                        + "revision+1,1,'case-approval-input-v1',?, ?,content_json,'PENDING_APPROVAL',"
                        + "created_by_membership_id,? FROM report_versions WHERE id=?",
                pinnedReportId, TEMPLATE_SHA256, CONTENT_SHA256, now, reportId);
        jdbc.update("UPDATE cases SET status='READY_FOR_APPROVAL', current_report_version_id=?, "
                + "case_version=case_version+1 WHERE id=?", pinnedReportId, scope.caseId());
        long after = before + 1;
        assertThat(insertRequest(jdbc, REQUEST_ID, scope, pinnedReportId, scope.snapshotId(),
                scope.revision() + 1, CONTENT_SHA256, after, scope.caseId(), now)).isEqualTo(1);
        insertAudit(jdbc, AUDIT_ID, scope, pinnedReportId, REQUEST_ID, CONTENT_SHA256, before, after, now);
        insertHold(jdbc, HOLD_ID, scope, now);

        assertThat(count(jdbc, "approval_requests")).isEqualTo(1);
        assertThat(count(jdbc, "approval_audit_events")).isEqualTo(1);
        assertThat(count(jdbc, "issuance_holds")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT current_report_version_id FROM cases WHERE id=?",
                String.class, scope.caseId())).isEqualTo(pinnedReportId);

        assertThatThrownBy(() -> insertRequest(jdbc, UUID.randomUUID().toString(), scope, pinnedReportId,
                scope.snapshotId(), scope.revision() + 1, CONTENT_SHA256, after, scope.caseId(), now))
                .isInstanceOf(DataIntegrityViolationException.class);

        String secondReport = UUID.randomUUID().toString();
        assertThat(jdbc.update("INSERT INTO report_versions (id,tenant_id,event_id,case_id,evidence_snapshot_id,"
                        + "evidence_revision,revision,version,template_version,template_sha256,content_sha256,"
                        + "content_json,status,created_by_membership_id,created_at) "
                        + "SELECT ?,tenant_id,event_id,case_id,evidence_snapshot_id,evidence_revision,"
                        + "revision+2,0,'case-approval-input-v1',?, ?,content_json,'DRAFT',"
                        + "created_by_membership_id,? FROM report_versions WHERE id=?",
                secondReport, TEMPLATE_SHA256, "7".repeat(64), now.plusSeconds(1), reportId)).isEqualTo(1);
        assertThatThrownBy(() -> insertRequest(jdbc, UUID.randomUUID().toString(), scope, secondReport,
                scope.snapshotId(), scope.revision() + 2, "7".repeat(64), after, null, now))
                .isInstanceOf(DataAccessException.class)
                .hasRootCauseInstanceOf(java.sql.SQLException.class)
                .satisfies(error -> {
                    java.sql.SQLException sqlException = (java.sql.SQLException)
                            ((DataAccessException) error).getMostSpecificCause();
                    assertThat(sqlException.getErrorCode()).isEqualTo(3819);
                    assertThat(sqlException.getMessage()).contains("chk_approval_request_active");
                });
        assertThat(count(jdbc, "approval_requests")).isEqualTo(1);
        assertThatThrownBy(() -> insertRequest(jdbc, UUID.randomUUID().toString(), scope, secondReport,
                scope.snapshotId(), scope.revision() + 2, "7".repeat(64), after, scope.caseId(), now))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertRequest(jdbc, UUID.randomUUID().toString(), scope,
                UUID.randomUUID().toString(), scope.snapshotId(), scope.revision(),
                scope.contentSha256(), after, scope.caseId(), now))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE approval_requests SET status='WITHDRAWN' WHERE id=?", REQUEST_ID))
                .isInstanceOf(DataAccessException.class)
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
        String withdrawnReport = UUID.randomUUID().toString();
        assertThat(jdbc.update("INSERT INTO report_versions (id,tenant_id,event_id,case_id,evidence_snapshot_id,"
                        + "evidence_revision,revision,version,template_version,template_sha256,content_sha256,"
                        + "content_json,status,created_by_membership_id,created_at) "
                        + "SELECT ?,tenant_id,event_id,case_id,evidence_snapshot_id,evidence_revision,"
                        + "revision+3,0,'case-approval-input-v1',?, ?,content_json,'WITHDRAWN',"
                        + "created_by_membership_id,? FROM report_versions WHERE id=?",
                withdrawnReport, TEMPLATE_SHA256, "6".repeat(64), now.plusSeconds(2), reportId)).isEqualTo(1);
        String withdrawnRequest = UUID.randomUUID().toString();
        assertThat(insertRequest(jdbc, withdrawnRequest, scope, withdrawnReport, scope.snapshotId(),
                scope.revision() + 3, "6".repeat(64), after, null, "WITHDRAWN", now)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT active_case_id FROM approval_requests WHERE id=?", String.class,
                withdrawnRequest)).isNull();
        assertThatThrownBy(() -> insertHold(jdbc, UUID.randomUUID().toString(),
                new Scope(scope.tenant(), UUID.randomUUID().toString(), scope.caseId(), scope.snapshotId(),
                        scope.actor(), scope.revision(), scope.contentSha256()), now))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static int insertRequest(JdbcTemplate jdbc, String id, Scope scope, String reportId,
            String snapshotId, long reportRevision, String contentSha, long caseVersion,
            String activeCaseId, LocalDateTime now) {
        return insertRequest(jdbc, id, scope, reportId, snapshotId, reportRevision, contentSha, caseVersion,
                activeCaseId, "PENDING", now);
    }

    private static int insertRequest(JdbcTemplate jdbc, String id, Scope scope, String reportId,
            String snapshotId, long reportRevision, String contentSha, long caseVersion,
            String activeCaseId, String status, LocalDateTime now) {
        return jdbc.update("INSERT INTO approval_requests (id,tenant_id,event_id,case_id,report_version_id,"
                        + "report_revision,report_content_sha256,report_template_version,"
                        + "report_template_sha256,evidence_snapshot_id,evidence_revision,manifest_sha256,"
                        + "requested_by_membership_id,requested_at,reason,status,case_version_at_submit,"
                        + "active_case_id,request_version) SELECT ?,?,?,?,?,?,?,?,?,?,s.evidence_revision,"
                        + "s.manifest_sha256,?,?,?,?,?,?,0 FROM evidence_snapshots s WHERE s.id=?",
                id, scope.tenant(), scope.event(), scope.caseId(), reportId, reportRevision, contentSha,
                "case-approval-input-v1", TEMPLATE_SHA256, snapshotId, scope.actor(), now,
                "Synthetic W08 migration request", status, caseVersion, activeCaseId, snapshotId);
    }

    private static void insertAudit(JdbcTemplate jdbc, String id, Scope scope, String reportId,
            String requestId, String contentSha256, long before, long after, LocalDateTime now) {
        jdbc.update("INSERT INTO approval_audit_events (id,tenant_id,event_id,case_id,approval_request_id,"
                        + "report_version_id,evidence_snapshot_id,actor_membership_id,event_type,from_status,"
                        + "to_status,report_content_sha256,manifest_sha256,evidence_revision,"
                        + "case_version_before,case_version_after,reason,occurred_at) "
                        + "SELECT ?,?,?,?,?,?,?,?,'APPROVAL_REQUESTED',NULL,'PENDING',?,s.manifest_sha256,"
                        + "s.evidence_revision,?,?,?,? FROM evidence_snapshots s WHERE s.id=?",
                id, scope.tenant(), scope.event(), scope.caseId(), requestId, reportId,
                scope.snapshotId(), scope.actor(), contentSha256, before, after,
                "Synthetic W08 approval audit", now, scope.snapshotId());
    }

    private static void insertHold(JdbcTemplate jdbc, String id, Scope scope, LocalDateTime now) {
        jdbc.update("INSERT INTO issuance_holds (id,tenant_id,event_id,case_id,status,reason_code,"
                        + "created_by_membership_id,evidence_revision,created_at,released_at,hold_version) "
                        + "SELECT ?,?,?,?,'ACTIVE','SYNTHETIC_HOLD',?,evidence_revision,?,NULL,0 "
                        + "FROM cases WHERE id=?",
                id, scope.tenant(), scope.event(), scope.caseId(), scope.actor(), now, scope.caseId());
    }

    private static DriverManagerDataSource dataSource() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                System.getenv("TD_ISSUE32_MYSQL_URL"), System.getenv("TD_ISSUE32_MYSQL_USER"),
                System.getenv("TD_ISSUE32_MYSQL_PASSWORD"));
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        return source;
    }

    private static int count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private record Scope(String tenant, String event, String caseId, String snapshotId,
                         String actor, long revision, String contentSha256) { }
}
