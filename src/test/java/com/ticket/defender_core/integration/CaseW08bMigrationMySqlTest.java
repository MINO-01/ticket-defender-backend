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
class CaseW08bMigrationMySqlTest {
    static final String RECEIPT_ID = "d8080808-0808-4808-8808-080808080808";
    static final String DECISION_ID = "e8080808-0808-4808-8808-080808080808";
    static final String RECEIPT_TOKEN_SHA256 = "a".repeat(64);

    @Test
    void additiveMigrationPreservesW08aRowsAndEnforcesReceiptDecisionAndAuditConstraints()
            throws Exception {
        DriverManagerDataSource source = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        int auditsBefore = count(jdbc, "approval_audit_events");
        int requestsBefore = count(jdbc, "approval_requests");
        Scope scope = jdbc.queryForObject("SELECT tenant_id,event_id,case_id,report_version_id,"
                        + "evidence_snapshot_id,requested_by_membership_id,case_version_at_submit "
                        + "FROM approval_requests WHERE id=?", (rs, row) -> new Scope(
                rs.getString("tenant_id"), rs.getString("event_id"), rs.getString("case_id"),
                rs.getString("report_version_id"), rs.getString("evidence_snapshot_id"),
                rs.getString("requested_by_membership_id"), rs.getLong("case_version_at_submit")),
                CaseW08aMigrationMySqlTest.REQUEST_ID);
        try (Connection connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("docs/database/case-w08b-migration.sql"));
        }

        assertThat(count(jdbc, "approval_requests")).isEqualTo(requestsBefore);
        assertThat(count(jdbc, "approval_audit_events")).isEqualTo(auditsBefore);
        assertThat(jdbc.queryForObject("SELECT review_receipt_id FROM approval_audit_events WHERE id=?",
                String.class, CaseW08aMigrationMySqlTest.AUDIT_ID)).isNull();
        assertThat(jdbc.queryForObject("SELECT approval_decision_id FROM approval_audit_events WHERE id=?",
                String.class, CaseW08aMigrationMySqlTest.AUDIT_ID)).isNull();

        LocalDateTime now = LocalDateTime.parse("2026-10-29T12:00:00");
        assertThat(insertReceipt(jdbc, RECEIPT_ID, scope, RECEIPT_TOKEN_SHA256,
                CaseW08aMigrationMySqlTest.REQUEST_ID, "ISSUED", now)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT active_approval_request_id FROM review_receipts WHERE id=?",
                String.class, RECEIPT_ID)).isEqualTo(CaseW08aMigrationMySqlTest.REQUEST_ID);
        assertThatThrownBy(() -> insertReceipt(jdbc, UUID.randomUUID().toString(), scope,
                "b".repeat(64), null, "ISSUED", now))
                .isInstanceOf(DataAccessException.class)
                .hasRootCauseInstanceOf(java.sql.SQLException.class)
                .satisfies(error -> assertCheck((DataAccessException) error, "chk_review_receipt_shape"));
        assertThatThrownBy(() -> insertReceipt(jdbc, UUID.randomUUID().toString(), scope,
                "c".repeat(64), CaseW08aMigrationMySqlTest.REQUEST_ID, "ISSUED", now))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertReceipt(jdbc, UUID.randomUUID().toString(), scope,
                RECEIPT_TOKEN_SHA256, null, "EXPIRED", now))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.update("UPDATE review_receipts SET status='CONSUMED', active_approval_request_id=NULL, "
                        + "consumed_at=? WHERE id=?", now.plusSeconds(5), RECEIPT_ID)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("UPDATE review_receipts SET active_approval_request_id=? WHERE id=?",
                CaseW08aMigrationMySqlTest.REQUEST_ID, RECEIPT_ID))
                .isInstanceOf(DataAccessException.class)
                .hasRootCauseInstanceOf(java.sql.SQLException.class)
                .satisfies(error -> assertCheck((DataAccessException) error, "chk_review_receipt_shape"));

        insertReceiptAudit(jdbc, UUID.randomUUID().toString(), scope, RECEIPT_ID, now);
        assertThat(insertDecision(jdbc, DECISION_ID, scope, RECEIPT_ID, now)).isEqualTo(1);
        insertDecisionAudit(jdbc, UUID.randomUUID().toString(), scope, RECEIPT_ID, DECISION_ID, now);
        assertThat(count(jdbc, "approval_decisions")).isEqualTo(1);
        assertThatThrownBy(() -> insertDecision(jdbc, UUID.randomUUID().toString(), scope, RECEIPT_ID,
                now.plusSeconds(1))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_audit_events WHERE approval_request_id=?",
                Integer.class, CaseW08aMigrationMySqlTest.REQUEST_ID)).isEqualTo(auditsBefore + 2);
    }

    private static int insertReceipt(JdbcTemplate jdbc, String id, Scope scope, String tokenSha256,
            String activeRequestId, String status, LocalDateTime now) {
        return jdbc.update("INSERT INTO review_receipts (id,tenant_id,event_id,case_id,approval_request_id,"
                        + "report_version_id,report_revision,report_content_sha256,report_template_version,"
                        + "report_template_sha256,evidence_snapshot_id,evidence_revision,manifest_sha256,"
                        + "approver_membership_id,token_sha256,status,issued_at,expires_at,consumed_at,"
                        + "revoked_at,active_approval_request_id,receipt_version) "
                        + "SELECT ?,r.tenant_id,r.event_id,r.case_id,r.id,r.report_version_id,r.report_revision,"
                        + "r.report_content_sha256,r.report_template_version,r.report_template_sha256,"
                        + "r.evidence_snapshot_id,r.evidence_revision,r.manifest_sha256,"
                        + "r.requested_by_membership_id,?,?,?,?,NULL,NULL,?,0 FROM approval_requests r WHERE r.id=?",
                id, tokenSha256, status, now, now.plusMinutes(10), activeRequestId,
                CaseW08aMigrationMySqlTest.REQUEST_ID);
    }

    private static void insertReceiptAudit(JdbcTemplate jdbc, String id, Scope scope, String receiptId,
            LocalDateTime now) {
        jdbc.update("INSERT INTO approval_audit_events (id,tenant_id,event_id,case_id,approval_request_id,"
                        + "review_receipt_id,approval_decision_id,report_version_id,evidence_snapshot_id,"
                        + "actor_membership_id,event_type,from_status,to_status,report_content_sha256,"
                        + "manifest_sha256,evidence_revision,case_version_before,case_version_after,reason,"
                        + "occurred_at) SELECT ?,r.tenant_id,r.event_id,r.case_id,r.id,?,NULL,r.report_version_id,"
                        + "r.evidence_snapshot_id,r.requested_by_membership_id,'REVIEW_RECEIPT_ISSUED','PENDING',"
                        + "'PENDING',r.report_content_sha256,r.manifest_sha256,r.evidence_revision,?,?,"
                        + "'Synthetic receipt audit',? FROM approval_requests r WHERE r.id=?",
                id, receiptId, scope.caseVersion(), scope.caseVersion(), now,
                CaseW08aMigrationMySqlTest.REQUEST_ID);
    }

    private static int insertDecision(JdbcTemplate jdbc, String id, Scope scope, String receiptId,
            LocalDateTime now) {
        return jdbc.update("INSERT INTO approval_decisions (id,tenant_id,event_id,case_id,approval_request_id,"
                        + "review_receipt_id,report_version_id,report_revision,report_content_sha256,"
                        + "report_template_version,report_template_sha256,evidence_snapshot_id,evidence_revision,"
                        + "manifest_sha256,outcome,reason,decided_by_membership_id,case_version_before,"
                        + "case_version_after,request_version_at_decision,report_version_at_decision,decided_at) "
                        + "SELECT ?,r.tenant_id,r.event_id,r.case_id,r.id,?,r.report_version_id,r.report_revision,"
                        + "r.report_content_sha256,r.report_template_version,r.report_template_sha256,"
                        + "r.evidence_snapshot_id,r.evidence_revision,r.manifest_sha256,'APPROVE',"
                        + "'Synthetic W08b decision',r.requested_by_membership_id,?,?,r.request_version,0,? "
                        + "FROM approval_requests r WHERE r.id=?",
                id, receiptId, scope.caseVersion(), scope.caseVersion() + 1, now,
                CaseW08aMigrationMySqlTest.REQUEST_ID);
    }

    private static void insertDecisionAudit(JdbcTemplate jdbc, String id, Scope scope, String receiptId,
            String decisionId, LocalDateTime now) {
        jdbc.update("INSERT INTO approval_audit_events (id,tenant_id,event_id,case_id,approval_request_id,"
                        + "review_receipt_id,approval_decision_id,report_version_id,evidence_snapshot_id,"
                        + "actor_membership_id,event_type,from_status,to_status,report_content_sha256,"
                        + "manifest_sha256,evidence_revision,case_version_before,case_version_after,reason,"
                        + "occurred_at) SELECT ?,r.tenant_id,r.event_id,r.case_id,r.id,?,?,r.report_version_id,"
                        + "r.evidence_snapshot_id,r.requested_by_membership_id,'APPROVAL_DECIDED','PENDING',"
                        + "'APPROVED',r.report_content_sha256,r.manifest_sha256,r.evidence_revision,?,?,"
                        + "'Synthetic decision audit',? FROM approval_requests r WHERE r.id=?",
                id, receiptId, decisionId, scope.caseVersion(), scope.caseVersion() + 1, now,
                CaseW08aMigrationMySqlTest.REQUEST_ID);
    }

    private static void assertCheck(DataAccessException error, String constraint) {
        java.sql.SQLException sql = (java.sql.SQLException) error.getMostSpecificCause();
        assertThat(sql.getErrorCode()).isEqualTo(3819);
        assertThat(sql.getMessage()).contains(constraint);
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

    private record Scope(String tenant, String event, String caseId, String reportId, String snapshotId,
                         String actor, long caseVersion) { }
}
