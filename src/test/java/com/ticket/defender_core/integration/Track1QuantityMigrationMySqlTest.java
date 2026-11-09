package com.ticket.defender_core.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Issue #81 마이그레이션을 격리 MySQL 8에 적용하고 DB 제약이 애플리케이션 없이도 유지되는지 확인합니다. */
@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MYSQL_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:13307/td_issue32.*")
class Track1QuantityMigrationMySqlTest {
    private static final List<String> POLICY_SCRIPTS = List.of("purchase-limit-policy-migration.sql",
            "purchase-limit-policy-semantics-migration.sql", "purchase-limit-policy-scope-migration.sql");

    private static final List<String> GENERATED_TABLES = List.of("track1_purchase_limit_shared_signal_observations", "track1_purchase_limit_run_clusters", "track1_purchase_limit_finding_reservations",
            "track1_purchase_limit_findings", "track1_purchase_limit_runs", "track1_purchase_ledger_errors", "track1_purchase_ledger_rows",
            "track1_purchase_ledger_batches", "purchase_limit_policy_audits", "purchase_limit_policies");

    @Test
    void additiveMigrationEnforcesLedgerConstraints() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource(System.getenv("TD_ISSUE32_MYSQL_URL"), System.getenv("TD_ISSUE32_MYSQL_USER"), System.getenv("TD_ISSUE32_MYSQL_PASSWORD"));
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        int reservations = jdbc.queryForObject("select count(*) from reservation_candidates", Integer.class);
        try (Connection connection = source.getConnection()) {
            // CI 첫 단계의 Hibernate 생성 테이블을 지우고, 운영과 같은 마이그레이션 순서로 다시 만듭니다.
            jdbc.execute("set foreign_key_checks=0");
            for (String table : GENERATED_TABLES) jdbc.execute("drop table if exists " + table);
            jdbc.execute("set foreign_key_checks=1");
            for (String script : POLICY_SCRIPTS) ScriptUtils.executeSqlScript(connection, new FileSystemResource("docs/database/" + script));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("docs/database/track1-purchase-ledger-migration.sql"));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("docs/database/track1-capstone-demo-v1-migration.sql"));
            // Hibernate-created current schemas already contain reference_purpose; the additive script must remain safe.
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("docs/database/track1-capstone-demo-v1-migration.sql"));
        }
        assertThat(jdbc.queryForObject("select count(*) from reservation_candidates", Integer.class)).isEqualTo(reservations);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.table_constraints where table_schema=database() "
                + "and table_name='track1_purchase_limit_shared_signal_observations' and constraint_name='PRIMARY'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.table_constraints where table_schema=database() "
                + "and table_name='track1_purchase_limit_shared_signal_observations' and constraint_name='fk_t1_signal_observation_run'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.table_constraints where table_schema=database() "
                + "and table_name='track1_purchase_limit_shared_signal_observations' and constraint_name='uk_t1_signal_observation_run_id'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.table_constraints where table_schema=database() "
                + "and table_name='track1_purchase_limit_shared_signal_observations' and constraint_name='chk_t1_signal_observation_values'", Integer.class)).isEqualTo(1);

        String tenant = UUID.randomUUID().toString();
        String event = UUID.randomUUID().toString();
        String member = UUID.randomUUID().toString();
        String performance = UUID.randomUUID().toString();
        String now = "2026-11-08 10:00:00";
        jdbc.update("insert into tenants(id,name,status) values(?,?,'ACTIVE')", tenant, "synthetic");
        jdbc.update("insert into events(id,tenant_id,external_event_ref,name,status,public_intake_enabled) values(?,?,?,?,'ACTIVE',b'0')", event, tenant, "e-" + event, "synthetic");
        jdbc.update("insert into memberships(id,tenant_id,subject,status) values(?,?,?,'ACTIVE')", member, tenant, "s-" + member);
        jdbc.update("insert into verified_performances(id,tenant_id,event_id,performance_ref,status,verification_basis,source_version,verified_by_membership_id,revision,verified_at,row_version) values(?,?,?,?,'ACTIVE','b','v',?,1,?,0)",
                performance, tenant, event, "r-" + performance, member, now);

        String batch = batch(jdbc, tenant, event, performance, member, "v1", 1, "ACCEPTED", "v1", "d".repeat(64), 1, now);
        row(jdbc, batch, 0, "a".repeat(64), "t".repeat(64), 1, "TICKET", "2026-11-02 10:00:00", now);
        // 같은 배치의 같은 티켓 토큰은 수량 중복 계산이 되므로 DB가 막습니다.
        assertThatThrownBy(() -> row(jdbc, batch, 1, "a".repeat(64), "t".repeat(64), 1, "TICKET", "2026-11-02 10:00:00", now)).isInstanceOf(DataAccessException.class);
        // TICKET 단위는 수량 1이어야 합니다.
        assertThatThrownBy(() -> row(jdbc, batch, 2, "a".repeat(64), "u".repeat(64), 3, "TICKET", "2026-11-02 10:00:00", now)).isInstanceOf(DataAccessException.class);
        // 기준시각 이후 구매는 저장되지 않습니다.
        assertThatThrownBy(() -> row(jdbc, batch, 3, "a".repeat(64), "w".repeat(64), 1, "TICKET", "2026-11-09 10:00:00", now)).isInstanceOf(DataAccessException.class);
        // ACCEPTED sourceVersion과 원장 버전은 회차 안에서 유일합니다.
        assertThatThrownBy(() -> batch(jdbc, tenant, event, performance, member, "v1", 2, "ACCEPTED", "v1", "d".repeat(64), 1, now)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> batch(jdbc, tenant, event, performance, member, "v2", 1, "ACCEPTED", "v2", "d".repeat(64), 1, now)).isInstanceOf(DataAccessException.class);
        // 거절 배치는 sourceVersion을 점유하지 않지만 수락 값을 가질 수 없습니다.
        batch(jdbc, tenant, event, performance, member, "v1", 3, "REJECTED", null, null, 0, now);
        assertThatThrownBy(() -> batch(jdbc, tenant, event, performance, member, "v3", 4, "REJECTED", "v3", null, 0, now)).isInstanceOf(DataAccessException.class);
        // 다른 고객사의 회차를 참조할 수 없습니다.
        assertThatThrownBy(() -> batch(jdbc, UUID.randomUUID().toString(), event, performance, member, "v4", 5, "ACCEPTED", "v4", "d".repeat(64), 1, now)).isInstanceOf(DataAccessException.class);
    }

    private static String batch(JdbcTemplate jdbc, String tenant, String event, String performance, String member, String source, int version, String status, String accepted, String digest, int acceptedRows, String now) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into track1_purchase_ledger_batches(id,tenant_id,event_id,performance_id,created_by_membership_id,idempotency_key_digest,request_digest,rows_digest,input_type,source_version,accepted_source_version,source_as_of,ledger_version,quantity_basis,reference_hmac_key_version,synthetic_isolation_id,complete_snapshot,status,input_row_count,accepted_row_count,rejected_row_count,created_at,row_version)"
                        + " values(?,?,?,?,?,?,?,?,'INDIVIDUAL_TICKET',?,?,?,?,'basis','v1',?,1,?,1,?,?,?,0)",
                id, tenant, event, performance, member, UUID.randomUUID().toString().replace("-", "") + "00000000000000000000000000000000", "r".repeat(64), digest, source, accepted, now, version, UUID.randomUUID().toString(), status, acceptedRows, acceptedRows == 1 ? 0 : 1, now);
        return id;
    }

    private static void row(JdbcTemplate jdbc, String batch, int index, String account, String ticket, int quantity, String unit, String purchased, String asOf) {
        jdbc.update("insert into track1_purchase_ledger_rows(id,batch_id,source_row_index,account_ref_hmac,reservation_ref_hmac,ticket_ref_hmac,purchased_at,ticket_quantity,quantity_unit,ticket_status,device_hmac,source_version,source_as_of) values(?,?,?,?,?,?,?,?,?,'ACTIVE',?,'v1',?)",
                UUID.randomUUID().toString(), batch, index, account, "b".repeat(64), ticket, purchased, quantity, unit, "c".repeat(64), asOf);
    }
}
