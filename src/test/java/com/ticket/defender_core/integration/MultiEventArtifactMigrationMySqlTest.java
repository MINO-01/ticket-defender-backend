package com.ticket.defender_core.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

/** Applies the MEI schema chain only after BL-07 in the disposable MySQL 8 CI database. */
@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MYSQL_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/td_issue32_(ci|bl01|minio_ci).*" )
class MultiEventArtifactMigrationMySqlTest {
    private static final String[] MIGRATIONS = {
            "bulk-ledger-score-v2-exp2-signal-migration.sql",
            "multi-event-investigation-migration.sql",
            "multi-event-quantity-snapshot-migration.sql",
            "multi-event-source-binding-migration.sql",
            "multi-event-artifact-job-migration.sql",
            "multi-event-exp3-quantity-evidence-migration.sql",
            "investigation-target-reveal-migration.sql"
    };

    @Test
    void appliesExp2AndMultiEventMigrationsInOrderAndConstrainsArtifactJobs() throws Exception {
        var source = new DriverManagerDataSource(System.getenv("TD_ISSUE32_MYSQL_URL"),
                System.getenv("TD_ISSUE32_MYSQL_USER"), System.getenv("TD_ISSUE32_MYSQL_PASSWORD"));
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        try (Connection connection = source.getConnection()) {
            for (String migration : MIGRATIONS) {
                ScriptUtils.executeSqlScript(connection, new FileSystemResource("docs/database/" + migration));
            }
        }

        assertColumns(jdbc, "td_bulk_ledger_track1_ticket_signals",
                "refund_account_hmac", "phone_hmac", "ip_network_type");
        assertNullableColumn(jdbc, "td_bulk_ledger_track1_ticket_signals", "refund_account_hmac");
        assertNullableColumn(jdbc, "td_bulk_ledger_track1_ticket_signals", "phone_hmac");
        assertNullableColumn(jdbc, "td_bulk_ledger_track1_ticket_signals", "ip_network_type");
        assertColumns(jdbc, "td_multi_event_investigation_runs", "data_job_id", "artifact_id",
                "artifact_object_version", "artifact_sha256", "artifact_record_count", "artifact_cancelled_ticket_count",
                "exp3_quantity_artifact_id", "exp3_quantity_artifact_object_version", "exp3_quantity_artifact_sha256",
                "exp3_quantity_artifact_record_count");
        assertCheck(jdbc, "chk_mei_artifact_binding");
        assertCheck(jdbc, "chk_mei_cancelled_artifact_count");
        assertCheck(jdbc, "chk_mei_exp3_quantity_artifact_binding");
        assertCheck(jdbc, "chk_mei_run_state");
        assertConstraint(jdbc, "td_multi_event_investigation_scopes", "fk_mei_scope_version_identity", "FOREIGN KEY");
        assertConstraint(jdbc, "td_multi_event_investigation_scopes", "fk_mei_scope_policy_snapshot_version", "FOREIGN KEY");
        assertIndex(jdbc, "td_multi_event_investigation_runs", "uk_mei_data_job", true);
        assertIndex(jdbc, "td_multi_event_investigation_runs", "idx_mei_data_claim", false);

        String runCheck = checkClause(jdbc, "chk_mei_run_state").toUpperCase(java.util.Locale.ROOT);
        assertThat(runCheck).contains("DATA_PENDING", "RUNNING", "LEASE_OWNER", "LEASE_UNTIL");
        String artifactCheck = checkClause(jdbc, "chk_mei_artifact_binding").toUpperCase(java.util.Locale.ROOT);
        assertThat(artifactCheck).contains("ARTIFACT_RECORD_COUNT", "400000", "DATA_JOB_ID");
        String quantityArtifactCheck = checkClause(jdbc, "chk_mei_exp3_quantity_artifact_binding").toUpperCase(java.util.Locale.ROOT);
        assertThat(quantityArtifactCheck).contains("EXP3_QUANTITY_ARTIFACT_RECORD_COUNT", "1200500", "ARTIFACT_ID");

        assertColumns(jdbc, "td_investigation_reference_ciphertexts", "reference_ciphertext", "nonce",
                "encryption_key_version", "ledger_version_id");
        assertCheck(jdbc, "chk_td_investigation_reference_values");
        assertCheck(jdbc, "chk_td_investigation_access_audit_values");
        assertCascade(jdbc, "fk_td_investigation_reference_tenant");
        assertCascade(jdbc, "fk_td_investigation_reference_job");
        assertCascade(jdbc, "fk_td_investigation_reference_event");
        assertCascade(jdbc, "fk_td_investigation_reference_performance");
        assertCascade(jdbc, "fk_td_investigation_reference_version_scope");
        assertCascade(jdbc, "fk_td_investigation_access_audit_tenant");
        assertCascade(jdbc, "fk_td_investigation_access_audit_event");
        assertIndex(jdbc, "td_investigation_reference_ciphertexts", "uk_td_investigation_reference_import_row", true);

        verifyReferenceRowsCascadeOnParentDeletion(jdbc);
    }

    private static void assertColumns(JdbcTemplate jdbc, String table, String... columns) {
        for (String column : columns) {
            assertThat(jdbc.queryForObject("select count(*) from information_schema.columns "
                    + "where table_schema=database() and table_name=? and column_name=?", Integer.class, table, column))
                    .as(table + "." + column).isEqualTo(1);
        }
    }

    private static void assertNullableColumn(JdbcTemplate jdbc, String table, String column) {
        assertThat(jdbc.queryForObject("select is_nullable from information_schema.columns "
                + "where table_schema=database() and table_name=? and column_name=?", String.class, table, column))
                .isEqualTo("YES");
    }

    private static void assertCheck(JdbcTemplate jdbc, String constraint) {
        assertThat(jdbc.queryForObject("select count(*) from information_schema.table_constraints "
                + "where constraint_schema=database() and constraint_name=? and constraint_type='CHECK'",
                Integer.class, constraint)).isEqualTo(1);
    }

    private static String checkClause(JdbcTemplate jdbc, String constraint) {
        return jdbc.queryForObject("select check_clause from information_schema.check_constraints "
                + "where constraint_schema=database() and constraint_name=?", String.class, constraint);
    }

    private static void assertConstraint(JdbcTemplate jdbc, String table, String constraint, String type) {
        assertThat(jdbc.queryForObject("select count(*) from information_schema.table_constraints "
                + "where constraint_schema=database() and table_name=? and constraint_name=? and constraint_type=?",
                Integer.class, table, constraint, type)).isEqualTo(1);
    }

    private static void assertIndex(JdbcTemplate jdbc, String table, String index, boolean unique) {
        Integer nonUnique = jdbc.queryForObject("select non_unique from information_schema.statistics "
                + "where table_schema=database() and table_name=? and index_name=? limit 1", Integer.class, table, index);
        assertThat(nonUnique).isNotNull();
        assertThat(nonUnique == 0).isEqualTo(unique);
    }

    private static void assertCascade(JdbcTemplate jdbc, String constraint) {
        assertThat(jdbc.queryForObject("select delete_rule from information_schema.referential_constraints "
                + "where constraint_schema=database() and constraint_name=?", String.class, constraint))
                .isEqualTo("CASCADE");
    }

    private static void verifyReferenceRowsCascadeOnParentDeletion(JdbcTemplate jdbc) {
        String tenant = java.util.UUID.randomUUID().toString();
        String event = java.util.UUID.randomUUID().toString();
        String member = java.util.UUID.randomUUID().toString();
        String performance = java.util.UUID.randomUUID().toString();
        String importId = java.util.UUID.randomUUID().toString();
        String ciphertextId = java.util.UUID.randomUUID().toString();
        String auditId = java.util.UUID.randomUUID().toString();

        jdbc.update("insert into tenants(id,name,status) values(?,?,'ACTIVE')", tenant, "synthetic reveal tenant");
        jdbc.update("insert into events(id,tenant_id,external_event_ref,name,status,public_intake_enabled) "
                + "values(?,?,?,?,'ACTIVE',b'0')", event, tenant, "reveal-" + event, "synthetic reveal event");
        jdbc.update("insert into memberships(id,tenant_id,subject,status) values(?,?,?,'ACTIVE')",
                member, tenant, "reveal-" + member);
        jdbc.update("insert into verified_performances(id,tenant_id,event_id,performance_ref,status,verification_basis,source_version,verified_by_membership_id,revision,verified_at,row_version) "
                + "values(?,?,?,?,'ACTIVE','basis','v1',?,1,utc_timestamp(6),0)",
                performance, tenant, event, "reveal-" + performance, member);
        jdbc.update("insert into td_bulk_import_jobs(id,tenant_id,created_by_membership_id,idempotency_key_digest,request_digest,schema_version,source_system,source_batch_id,mode,format,declared_object_sha256,declared_file_bytes,declared_row_count,complete_snapshot,status,processed_row_count,accepted_row_count,rejected_row_count,attempt_count,fence_token,created_at,updated_at,row_version) "
                        + "values(?,?,?,?,'request','v1','SYNTHETIC','reveal-batch','FULL_SNAPSHOT','CSV',?,0,1,1,'CREATED',0,0,0,0,0,utc_timestamp(6),utc_timestamp(6),0)",
                importId, tenant, member, "a".repeat(64), "b".repeat(64));
        jdbc.update("insert into td_investigation_reference_ciphertexts(id,import_id,tenant_id,event_id,performance_id,source_row_no,ticket_reference_hmac,reservation_reference_hmac,account_reference_hmac,reference_ciphertext,nonce,encryption_key_version,ledger_version_id,created_at) "
                        + "values(?,?,?,?,?,1,?,?,?,?,?,'SYNTHETIC_V1',null,utc_timestamp(6))",
                ciphertextId, importId, tenant, event, performance, "c".repeat(64), "d".repeat(64), "e".repeat(64),
                new byte[16], new byte[12]);
        jdbc.update("insert into td_investigation_reference_access_audit(id,tenant_id,event_id,actor_subject,target_type,resource_id,action,target_count,reason,accessed_at) "
                        + "values(?,?,?,'synthetic-admin','MULTI_EVENT','synthetic-run','REVEAL',1,'synthetic reason',utc_timestamp(6))",
                auditId, tenant, event);

        jdbc.update("delete from verified_performances where id=?", performance);
        assertThat(jdbc.queryForObject("select count(*) from td_investigation_reference_ciphertexts where id=?", Integer.class, ciphertextId))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from td_investigation_reference_access_audit where id=?", Integer.class, auditId))
                .isEqualTo(1);

        jdbc.update("delete from events where id=?", event);
        assertThat(jdbc.queryForObject("select count(*) from td_investigation_reference_access_audit where id=?", Integer.class, auditId))
                .isZero();
        jdbc.update("delete from td_bulk_import_jobs where id=?", importId);
        jdbc.update("delete from memberships where id=?", member);
        jdbc.update("delete from tenants where id=?", tenant);
    }
}
