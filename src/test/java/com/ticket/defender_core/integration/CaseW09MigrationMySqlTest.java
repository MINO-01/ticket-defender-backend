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
