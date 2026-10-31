package com.ticket.defender_core.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MYSQL_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:13307/td_issue32.*")
class CaseW09MigrationMySqlTest {
    static final String ISSUANCE_ID = "f9090909-0909-4909-8909-090909090909";
    static final String ARTIFACT_ID = "a9090909-0909-4909-8909-090909090909";

    @Test
    void additiveMigrationSeparatesRetryableIssuanceFromImmutableArtifactAndEnforcesUniqueness()
            throws Exception {
        DriverManagerDataSource source = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        int decisionsBefore = count(jdbc, "approval_decisions");
        try (Connection connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("docs/database/case-w09-official-pdf-migration.sql"));
        }
        assertThat(count(jdbc, "approval_decisions")).isEqualTo(decisionsBefore);
        LocalDateTime now = LocalDateTime.parse("2026-10-30T09:00:00");
        assertThat(insertIssuance(jdbc, ISSUANCE_ID, CaseW08bMigrationMySqlTest.DECISION_ID, now)).isEqualTo(1);
        assertThatThrownBy(() -> insertIssuance(jdbc, UUID.randomUUID().toString(),
                CaseW08bMigrationMySqlTest.DECISION_ID, now.plusSeconds(1)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE official_pdf_issuances SET status='GENERATED', completed_at=? WHERE id=?",
                now, ISSUANCE_ID)).isInstanceOf(DataAccessException.class).satisfies(error -> assertCheck(
                (DataAccessException) error, "chk_official_pdf_issuance_candidate"));

        assertThat(jdbc.update("UPDATE official_pdf_issuances SET status='OBJECT_STORED',attempt_count=1,"
                        + "candidate_object_version='synthetic-version-1',candidate_pdf_sha256=?,candidate_pdf_size=17 "
                        + "WHERE id=?", "b".repeat(64), ISSUANCE_ID)).isEqualTo(1);
        assertThat(jdbc.update("INSERT INTO official_pdf_issuance_attempts (id,issuance_id,attempt_number,"
                        + "attempt_kind,status,actor_membership_id,failure_code,started_at,finished_at) "
                        + "SELECT ?,i.id,1,'REQUEST','SUCCEEDED',i.requested_by_membership_id,NULL,?,? "
                        + "FROM official_pdf_issuances i WHERE i.id=?", UUID.randomUUID().toString(), now, now.plusSeconds(1),
                ISSUANCE_ID)).isEqualTo(1);
        assertArtifactRejectsOtherDecisionInSameCase(source, now);
        assertThat(insertArtifact(jdbc, ARTIFACT_ID, ISSUANCE_ID, now.plusSeconds(2))).isEqualTo(1);
        assertThatThrownBy(() -> insertArtifact(jdbc, UUID.randomUUID().toString(), ISSUANCE_ID,
                now.plusSeconds(3))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.update("UPDATE official_pdf_issuances SET status='GENERATED',completed_at=? WHERE id=?",
                now.plusSeconds(2), ISSUANCE_ID)).isEqualTo(1);
        assertThat(jdbc.update("INSERT INTO official_pdf_audit_events (id,tenant_id,event_id,case_id,issuance_id,"
                        + "artifact_id,actor_membership_id,event_type,detail_code,occurred_at) "
                        + "SELECT ?,i.tenant_id,i.event_id,i.case_id,i.id,?,i.requested_by_membership_id,"
                        + "'ISSUANCE_GENERATED','CURRENT',? FROM official_pdf_issuances i WHERE i.id=?",
                UUID.randomUUID().toString(), ARTIFACT_ID, now.plusSeconds(2), ISSUANCE_ID)).isEqualTo(1);
        assertThat(count(jdbc, "official_pdf_issuances")).isEqualTo(1);
        assertThat(count(jdbc, "official_pdf_artifacts")).isEqualTo(1);
    }

    private static int insertIssuance(JdbcTemplate jdbc, String id, String decisionId, LocalDateTime now) {
        return jdbc.update("INSERT INTO official_pdf_issuances (id,tenant_id,event_id,case_id,approval_decision_id,"
                        + "approval_request_id,review_receipt_id,report_version_id,report_revision,report_content_sha256,"
                        + "report_template_version,report_template_sha256,evidence_snapshot_id,evidence_revision,manifest_sha256,"
                        + "official_template_version,official_template_sha256,candidate_object_key,status,attempt_count,"
                        + "candidate_object_version,candidate_pdf_sha256,candidate_pdf_size,last_failure_code,"
                        + "requested_by_membership_id,requested_at,completed_at,issuance_version) "
                        + "SELECT ?,d.tenant_id,d.event_id,d.case_id,d.id,d.approval_request_id,d.review_receipt_id,"
                        + "d.report_version_id,d.report_revision,d.report_content_sha256,d.report_template_version,"
                        + "d.report_template_sha256,d.evidence_snapshot_id,d.evidence_revision,d.manifest_sha256,"
                        + "'official-case-pdf-v1',?,CONCAT('official-pdf/',d.id,'.pdf'),'RESERVED',0,NULL,NULL,NULL,NULL,"
                        + "d.decided_by_membership_id,?,NULL,0 FROM approval_decisions d WHERE d.id=?",
                id, "c".repeat(64), now, decisionId);
    }

    private static int insertArtifact(JdbcTemplate jdbc, String artifactId, String issuanceId, LocalDateTime now) {
        return jdbc.update("INSERT INTO official_pdf_artifacts (id,tenant_id,event_id,case_id,issuance_id,"
                        + "approval_decision_id,approval_request_id,review_receipt_id,report_version_id,report_revision,"
                        + "report_content_sha256,report_template_version,report_template_sha256,evidence_snapshot_id,"
                        + "evidence_revision,manifest_sha256,official_template_version,official_template_sha256,"
                        + "object_key,object_version,pdf_sha256,pdf_size,generated_at) "
                        + "SELECT ?,i.tenant_id,i.event_id,i.case_id,i.id,i.approval_decision_id,i.approval_request_id,"
                        + "i.review_receipt_id,i.report_version_id,i.report_revision,i.report_content_sha256,"
                        + "i.report_template_version,i.report_template_sha256,i.evidence_snapshot_id,i.evidence_revision,"
                        + "i.manifest_sha256,i.official_template_version,i.official_template_sha256,i.candidate_object_key,"
                        + "i.candidate_object_version,i.candidate_pdf_sha256,i.candidate_pdf_size,? "
                        + "FROM official_pdf_issuances i WHERE i.id=?", artifactId, now, issuanceId);
    }

    /**
     * Builds a second, real REJECT decision chain in the same Case and tries to bind it to the existing
     * issuance. Everything runs in one transaction that is rolled back, so later CI steps see no extra rows.
     */
    private static void assertArtifactRejectsOtherDecisionInSameCase(DriverManagerDataSource source,
            LocalDateTime now) throws Exception {
        String reportId = UUID.randomUUID().toString();
        String requestId = UUID.randomUUID().toString();
        String receiptId = UUID.randomUUID().toString();
        String decisionId = UUID.randomUUID().toString();
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try {
                JdbcTemplate tx = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                assertThat(tx.update("INSERT INTO report_versions (id,tenant_id,event_id,case_id,evidence_snapshot_id,"
                                + "evidence_revision,revision,version,template_version,template_sha256,content_sha256,"
                                + "content_json,status,created_by_membership_id,created_at) "
                                + "SELECT ?,r.tenant_id,r.event_id,r.case_id,r.evidence_snapshot_id,r.evidence_revision,"
                                + "(SELECT MAX(x.revision)+1 FROM report_versions x WHERE x.case_id=r.case_id),0,"
                                + "r.template_version,r.template_sha256,?,r.content_json,'REJECTED',"
                                + "r.created_by_membership_id,? FROM report_versions r JOIN approval_decisions d "
                                + "ON d.report_version_id=r.id WHERE d.id=?",
                        reportId, "d".repeat(64), now, CaseW08bMigrationMySqlTest.DECISION_ID)).isEqualTo(1);
                assertThat(tx.update("INSERT INTO approval_requests (id,tenant_id,event_id,case_id,report_version_id,"
                                + "report_revision,report_content_sha256,report_template_version,report_template_sha256,"
                                + "evidence_snapshot_id,evidence_revision,manifest_sha256,requested_by_membership_id,"
                                + "requested_at,reason,status,case_version_at_submit,active_case_id,request_version) "
                                + "SELECT ?,r.tenant_id,r.event_id,r.case_id,r.id,r.revision,r.content_sha256,"
                                + "r.template_version,r.template_sha256,r.evidence_snapshot_id,r.evidence_revision,"
                                + "d.manifest_sha256,r.created_by_membership_id,?,'Synthetic second request','REJECTED',"
                                + "d.case_version_after,NULL,1 FROM report_versions r, approval_decisions d "
                                + "WHERE r.id=? AND d.id=?",
                        requestId, now, reportId, CaseW08bMigrationMySqlTest.DECISION_ID)).isEqualTo(1);
                assertThat(tx.update("INSERT INTO review_receipts (id,tenant_id,event_id,case_id,approval_request_id,"
                                + "report_version_id,report_revision,report_content_sha256,report_template_version,"
                                + "report_template_sha256,evidence_snapshot_id,evidence_revision,manifest_sha256,"
                                + "approver_membership_id,token_sha256,status,issued_at,expires_at,consumed_at,"
                                + "revoked_at,active_approval_request_id,receipt_version) "
                                + "SELECT ?,r.tenant_id,r.event_id,r.case_id,r.id,r.report_version_id,r.report_revision,"
                                + "r.report_content_sha256,r.report_template_version,r.report_template_sha256,"
                                + "r.evidence_snapshot_id,r.evidence_revision,r.manifest_sha256,"
                                + "r.requested_by_membership_id,?,'CONSUMED',?,?,?,NULL,NULL,1 "
                                + "FROM approval_requests r WHERE r.id=?",
                        receiptId, "e".repeat(64), now, now.plusMinutes(10), now.plusSeconds(1), requestId))
                        .isEqualTo(1);
                assertThat(tx.update("INSERT INTO approval_decisions (id,tenant_id,event_id,case_id,"
                                + "approval_request_id,review_receipt_id,report_version_id,report_revision,"
                                + "report_content_sha256,report_template_version,report_template_sha256,"
                                + "evidence_snapshot_id,evidence_revision,manifest_sha256,outcome,reason,"
                                + "decided_by_membership_id,case_version_before,case_version_after,"
                                + "request_version_at_decision,report_version_at_decision,decided_at) "
                                + "SELECT ?,r.tenant_id,r.event_id,r.case_id,r.id,?,r.report_version_id,r.report_revision,"
                                + "r.report_content_sha256,r.report_template_version,r.report_template_sha256,"
                                + "r.evidence_snapshot_id,r.evidence_revision,r.manifest_sha256,'REJECT',"
                                + "'Synthetic second decision',r.requested_by_membership_id,r.case_version_at_submit,"
                                + "r.case_version_at_submit+1,0,0,? FROM approval_requests r WHERE r.id=?",
                        decisionId, receiptId, now.plusSeconds(1), requestId)).isEqualTo(1);

                assertThatThrownBy(() -> tx.update("INSERT INTO official_pdf_artifacts (id,tenant_id,event_id,"
                                + "case_id,issuance_id,approval_decision_id,approval_request_id,review_receipt_id,"
                                + "report_version_id,report_revision,report_content_sha256,report_template_version,"
                                + "report_template_sha256,evidence_snapshot_id,evidence_revision,manifest_sha256,"
                                + "official_template_version,official_template_sha256,object_key,object_version,"
                                + "pdf_sha256,pdf_size,generated_at) "
                                + "SELECT ?,i.tenant_id,i.event_id,i.case_id,i.id,?,i.approval_request_id,"
                                + "i.review_receipt_id,i.report_version_id,i.report_revision,i.report_content_sha256,"
                                + "i.report_template_version,i.report_template_sha256,i.evidence_snapshot_id,"
                                + "i.evidence_revision,i.manifest_sha256,i.official_template_version,"
                                + "i.official_template_sha256,i.candidate_object_key,i.candidate_object_version,"
                                + "i.candidate_pdf_sha256,i.candidate_pdf_size,? FROM official_pdf_issuances i WHERE i.id=?",
                        UUID.randomUUID().toString(), decisionId, now.plusSeconds(2), ISSUANCE_ID))
                        .isInstanceOf(DataIntegrityViolationException.class)
                        .satisfies(error -> {
                            java.sql.SQLException sql = (java.sql.SQLException)
                                    ((DataAccessException) error).getMostSpecificCause();
                            assertThat(sql.getErrorCode()).isEqualTo(1452);
                            assertThat(sql.getMessage()).contains("fk_official_pdf_artifact_issuance");
                        });
            } finally {
                connection.rollback();
                connection.setAutoCommit(true);
            }
        }
    }

    private static void assertCheck(DataAccessException error, String constraint) {
        java.sql.SQLException sql = (java.sql.SQLException) error.getMostSpecificCause();
        assertThat(sql.getErrorCode()).isEqualTo(3819);
        assertThat(sql.getMessage()).contains(constraint);
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
}
