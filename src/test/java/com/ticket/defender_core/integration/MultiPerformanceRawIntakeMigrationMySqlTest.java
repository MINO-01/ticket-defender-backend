package com.ticket.defender_core.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import com.ticket.defender_core.application.service.MultiEventInvestigationService;

import java.sql.Connection;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 원본 신호 출처 컬럼과 다회차 점수 버전 migration을 MEI artifact 체인 다음에 적용합니다.
 * 같은 일회용 CI DB에서 Data의 ip_window_token과 백엔드 IP 시간창 SQL 구간이 같은지도 경계 시각 벡터로 확인합니다.
 */
@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MYSQL_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/td_issue32_(ci|bl01).*")
class MultiPerformanceRawIntakeMigrationMySqlTest {
    private static final String[] MIGRATIONS = {
            "bulk-ledger-track1-signal-source-migration.sql",
            "multi-event-multi-performance-migration.sql"
    };

    /** Data의 int(purchased_at.timestamp()) // 600 으로 계산한 기대 구간입니다. */
    private static final Map<String, Long> BOUNDARY_VECTORS = Map.of(
            "2026-01-01 00:09:59.999999", 2_945_376L,
            "2026-01-01 00:10:00.000000", 2_945_377L,
            "2026-01-01 00:19:59.000000", 2_945_377L,
            "2026-01-01 00:20:00.000000", 2_945_378L,
            "1970-01-01 00:09:59.000000", 0L,
            "2026-11-25 23:59:59.500000", 2_992_751L);

    @Test
    void appliesSignalSourceAndMultiPerformanceConstraintsAfterTheMeiChain() throws Exception {
        var source = new DriverManagerDataSource(System.getenv("TD_ISSUE32_MYSQL_URL"),
                System.getenv("TD_ISSUE32_MYSQL_USER"), System.getenv("TD_ISSUE32_MYSQL_PASSWORD"));
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        try (Connection connection = source.getConnection()) {
            for (String migration : MIGRATIONS)
                ScriptUtils.executeSqlScript(connection, new FileSystemResource("docs/database/" + migration));
        }
        for (String column : new String[] {"phone_source", "ip_network_type_source"}) {
            assertThat(jdbc.queryForObject("select is_nullable from information_schema.columns where table_schema=database() and table_name='td_bulk_ledger_track1_ticket_signals' and column_name=?",
                    String.class, column)).isEqualTo("YES");
        }
        assertThat(checkClause(jdbc, "td_bulk_ledger_track1_ticket_signals", "chk_td_bl06_signal_sources")).contains("RECIPIENT", "DECLARED");
        assertThat(checkClause(jdbc, "td_multi_event_investigation_runs", "chk_mei_run_state"))
                .contains("score-v2-exp2-multi-performance-recurrence", "score-v2-exp2-multi-event-recurrence");
        assertThat(checkClause(jdbc, "td_multi_event_investigation_candidates", "chk_mei_candidate_values"))
                .containsPattern("distinct_event_count`?\\s*>=\\s*1");
    }

    @Test
    void ipWindowBucketSqlMatchesTheDataTokenBoundaryVectors() {
        var source = new DriverManagerDataSource(System.getenv("TD_ISSUE32_MYSQL_URL"),
                System.getenv("TD_ISSUE32_MYSQL_USER"), System.getenv("TD_ISSUE32_MYSQL_PASSWORD"));
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        // 세션 시간대가 달라도 DATETIME 기반 구간은 바뀌지 않아야 합니다.
        for (String zone : new String[] {"+00:00", "+09:00"}) {
            jdbc.execute("set time_zone='" + zone + "'");
            for (var vector : BOUNDARY_VECTORS.entrySet()) {
                String expression = MultiEventInvestigationService.IP_WINDOW_BUCKET_SQL.formatted("cast(? as datetime(6))");
                Long bucket = jdbc.queryForObject("select " + expression, Long.class, vector.getKey());
                assertThat(bucket).as(vector.getKey() + " " + zone).isEqualTo(vector.getValue());
            }
        }
    }

    private static String checkClause(JdbcTemplate jdbc, String table, String constraint) {
        return jdbc.queryForObject("""
                select c.check_clause from information_schema.check_constraints c
                join information_schema.table_constraints t on t.constraint_schema=c.constraint_schema and t.constraint_name=c.constraint_name
                where c.constraint_schema=database() and t.table_name=? and c.constraint_name=?
                """, String.class, table, constraint);
    }
}
