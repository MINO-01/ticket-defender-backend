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

/** 원본 열람 감사 마이그레이션 다음에 합성 격리 등록부를 추가하고, 기존 데이터 보존과 빈 등록부, FK와 CHECK 제약을 MySQL 8에서 확인합니다. */
@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MYSQL_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:13307/td_issue32.*")
class OfficialPdfSyntheticIsolationMigrationMySqlTest {
    private static final LocalDateTime NOW = LocalDateTime.parse("2026-11-03T12:00:00");

    @Test
    void additiveRegistryStartsEmptyAndRejectsUnknownTenantsOrMalformedIsolation() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource(
                System.getenv("TD_ISSUE32_MYSQL_URL"), System.getenv("TD_ISSUE32_MYSQL_USER"),
                System.getenv("TD_ISSUE32_MYSQL_PASSWORD"));
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        int priorTenants = count(jdbc, "tenants");
        int priorIssuances = count(jdbc, "official_pdf_issuances");
        int priorArtifacts = count(jdbc, "official_pdf_artifacts");
        try (Connection connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("docs/database/official-pdf-synthetic-isolation-migration.sql"));
        }
        // 기존 고객사와 공식 PDF 이력은 바뀌지 않고, 등록부는 비어 있어 기존 데이터는 합성 발급 대상이 아닙니다.
        assertThat(count(jdbc, "tenants")).isEqualTo(priorTenants);
        assertThat(count(jdbc, "official_pdf_issuances")).isEqualTo(priorIssuances);
        assertThat(count(jdbc, "official_pdf_artifacts")).isEqualTo(priorArtifacts);
        assertThat(count(jdbc, "official_pdf_synthetic_tenants")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.referential_constraints "
                + "WHERE constraint_schema=DATABASE() AND constraint_name='fk_official_pdf_synthetic_tenant'",
                Integer.class)).isEqualTo(1);

        String tenant = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO tenants (id, name, status) VALUES (?, 'Synthetic #66 tenant', 'ACTIVE')", tenant);
        jdbc.update("INSERT INTO official_pdf_synthetic_tenants (tenant_id, isolation_id, registered_at) VALUES (?,?,?)",
                tenant, UUID.randomUUID().toString(), NOW);
        assertThat(count(jdbc, "official_pdf_synthetic_tenants")).isEqualTo(1);
        // 고객사당 등록은 하나이며 존재하지 않는 고객사나 형식이 틀린 격리 식별자는 저장할 수 없습니다.
        assertThatThrownBy(() -> jdbc.update("INSERT INTO official_pdf_synthetic_tenants "
                + "(tenant_id, isolation_id, registered_at) VALUES (?,?,?)", tenant, UUID.randomUUID().toString(), NOW))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO official_pdf_synthetic_tenants "
                        + "(tenant_id, isolation_id, registered_at) VALUES (?,?,?)",
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), NOW))
                .isInstanceOf(DataIntegrityViolationException.class);
        String other = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO tenants (id, name, status) VALUES (?, 'Synthetic #66 other', 'ACTIVE')", other);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO official_pdf_synthetic_tenants "
                + "(tenant_id, isolation_id, registered_at) VALUES (?,?,?)", other, "NOT-A-UUID", NOW))
                .isInstanceOf(DataAccessException.class);

        // 등록 절차처럼 registered_at을 생략하면 음수 오프셋 세션에서도 세션 시각(NOW())이 아니라 UTC가 기록됩니다.
        String utcTenant = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO tenants (id, name, status) VALUES (?, 'Synthetic #66 UTC tenant', 'ACTIVE')", utcTenant);
        try (Connection connection = source.getConnection();
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("SET time_zone = '-09:00'");
            try (java.sql.PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO official_pdf_synthetic_tenants (tenant_id, isolation_id) VALUES (?, ?)")) {
                insert.setString(1, utcTenant);
                insert.setString(2, UUID.randomUUID().toString());
                insert.executeUpdate();
            }
            try (java.sql.ResultSet result = statement.executeQuery("SELECT "
                    + "TIMESTAMPDIFF(MICROSECOND, registered_at, UTC_TIMESTAMP(6)), "
                    + "TIMESTAMPDIFF(HOUR, NOW(6), UTC_TIMESTAMP(6)) "
                    + "FROM official_pdf_synthetic_tenants WHERE tenant_id = '" + utcTenant + "'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isBetween(0L, 60_000_000L);
                // 같은 세션의 NOW()는 UTC보다 9시간 늦어, 직접 지정했다면 등록 이전 데이터를 허용했을 것입니다.
                assertThat(result.getLong(2)).isEqualTo(9L);
            }
        }

        // 이후 CI 단계의 고객사 데이터에 영향을 주지 않도록 이 테스트가 만든 행만 정리합니다.
        jdbc.update("DELETE FROM official_pdf_synthetic_tenants WHERE tenant_id IN (?, ?)", tenant, utcTenant);
        jdbc.update("DELETE FROM tenants WHERE id IN (?, ?, ?)", tenant, other, utcTenant);
        assertThat(count(jdbc, "official_pdf_synthetic_tenants")).isZero();
    }

    private static int count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
