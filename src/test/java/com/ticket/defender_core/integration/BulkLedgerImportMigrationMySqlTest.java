package com.ticket.defender_core.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs only against the disposable CI MySQL schema, never a user database. */
@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MYSQL_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/td_issue32_(ci|bl01).*" )
class BulkLedgerImportMigrationMySqlTest {
    @Test
    void additiveMigrationEnforcesJobScopeAndErrorConstraints() throws Exception {
        var source = new DriverManagerDataSource(System.getenv("TD_ISSUE32_MYSQL_URL"), System.getenv("TD_ISSUE32_MYSQL_USER"), System.getenv("TD_ISSUE32_MYSQL_PASSWORD"));
        source.setDriverClassName("com.mysql.cj.jdbc.Driver"); JdbcTemplate jdbc = new JdbcTemplate(source);
        try (Connection connection = source.getConnection()) {
            jdbc.execute("set foreign_key_checks=0");
            jdbc.execute("drop table if exists td_bulk_import_staging_rows"); jdbc.execute("drop table if exists td_bulk_import_errors"); jdbc.execute("drop table if exists td_bulk_import_scopes"); jdbc.execute("drop table if exists td_bulk_import_jobs");
            jdbc.execute("set foreign_key_checks=1");
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("docs/database/bulk-ledger-import-bl01-migration.sql"));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("docs/database/bulk-ledger-import-bl02-staging-migration.sql"));
        }
        assertAsciiBinaryUuidColumn(jdbc, "td_bulk_import_jobs", "tenant_id");
        assertAsciiBinaryUuidColumn(jdbc, "td_bulk_import_jobs", "created_by_membership_id");
        assertAsciiBinaryUuidColumn(jdbc, "td_bulk_import_scopes", "performance_id");
        String tenant = UUID.randomUUID().toString(), event = UUID.randomUUID().toString(), member = UUID.randomUUID().toString(), performance = UUID.randomUUID().toString();
        jdbc.update("insert into tenants(id,name,status) values(?,?,'ACTIVE')", tenant, "synthetic bulk tenant");
        jdbc.update("insert into events(id,tenant_id,external_event_ref,name,status,public_intake_enabled) values(?,?,?,?,'ACTIVE',b'0')", event, tenant, "bulk-" + event, "synthetic bulk event");
        jdbc.update("insert into memberships(id,tenant_id,subject,status) values(?,?,?,'ACTIVE')", member, tenant, "bulk-" + member);
        jdbc.update("insert into verified_performances(id,tenant_id,event_id,performance_ref,status,verification_basis,source_version,verified_by_membership_id,revision,verified_at,row_version) values(?,?,?,?,'ACTIVE','basis','v1',?,1,utc_timestamp(6),0)", performance, tenant, event, "p-" + performance, member);
        String job = job(jdbc, tenant, member, "SYNTHETIC", "batch-1", "FULL_SNAPSHOT", 1);
        scope(jdbc, job, tenant, event, performance, "2026-11-01 00:00:00", null, null, 1);
        assertThatThrownBy(() -> job(jdbc, tenant, member, "SYNTHETIC", "batch-1", "FULL_SNAPSHOT", 1)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> scope(jdbc, job, tenant, event, performance, "2026-11-01 00:00:00", null, null, 1)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> scope(jdbc, job, UUID.randomUUID().toString(), event, performance, "2026-11-01 00:00:00", null, null, 1)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("insert into td_bulk_import_errors(id,job_id,sequence_no,code,created_at) values(?,?,?,'CODE',utc_timestamp(6))", UUID.randomUUID().toString(), job, -1)).isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("select count(*) from td_bulk_import_jobs", Integer.class)).isEqualTo(1);
        jdbc.update("update td_bulk_import_jobs set upload_object_key_digest=?, upload_object_version=?, status='UPLOADED' where id=?", "b".repeat(64), "version-1", job);
        jdbc.update("insert into td_bulk_import_staging_rows(id,import_id,tenant_id,event_id,performance_id,source_row_no,reservation_reference_hmac,ticket_reference_hmac,hmac_key_version,ticket_quantity) values(?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), job, tenant, event, performance, 2, "a".repeat(64), "b".repeat(64), "v1", 1);
        assertThatThrownBy(() -> jdbc.update("insert into td_bulk_import_staging_rows(id,import_id,tenant_id,event_id,performance_id,source_row_no,reservation_reference_hmac,ticket_reference_hmac,hmac_key_version,ticket_quantity) values(?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), job, tenant, event, performance, 2, "a".repeat(64), "b".repeat(64), "v1", 1)).isInstanceOf(DataAccessException.class);
    }

    private static void assertAsciiBinaryUuidColumn(JdbcTemplate jdbc, String table, String column) {
        Map<String, Object> definition = jdbc.queryForMap("""
                select character_set_name, collation_name, character_maximum_length
                from information_schema.columns
                where table_schema = database() and table_name = ? and column_name = ?
                """, table, column);
        assertThat(definition.get("character_set_name")).isEqualTo("ascii");
        assertThat(definition.get("collation_name")).isEqualTo("ascii_bin");
        assertThat(((Number) definition.get("character_maximum_length")).intValue()).isEqualTo(36);
    }

    private static String job(JdbcTemplate jdbc, String tenant, String member, String source, String batch, String mode, long rows) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into td_bulk_import_jobs(id,tenant_id,created_by_membership_id,idempotency_key_digest,request_digest,schema_version,source_system,source_batch_id,mode,format,declared_object_sha256,declared_file_bytes,declared_row_count,complete_snapshot,status,processed_row_count,accepted_row_count,rejected_row_count,attempt_count,fence_token,created_at,updated_at,row_version) values(?,?,?,?,'r','v1',?,?,?,'CSV',?,0,?,1,'CREATED',0,0,0,0,0,utc_timestamp(6),utc_timestamp(6),0)", id, tenant, member, UUID.randomUUID().toString().replace("-", "") + "0".repeat(32), source, batch, mode, "a".repeat(64), rows);
        return id;
    }
    private static void scope(JdbcTemplate jdbc, String job, String tenant, String event, String performance, String asOf, String start, String end, long rows) {
        jdbc.update("insert into td_bulk_import_scopes(id,job_id,tenant_id,event_id,performance_id,scope_as_of,declared_row_count,cursor_start,cursor_end) values(?,?,?,?,?,?,?,?,?)", UUID.randomUUID().toString(), job, tenant, event, performance, asOf, rows, start, end);
    }
}
