package com.ticket.defender_core.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Local-only disposable-schema bootstrap. CI instead validates against its full ordered migration chain.
 * This class is impossible to enable without both the explicit opt-in and the dedicated BL-01 database name.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create")
@EnabledIfEnvironmentVariable(named = "TD_BL01_LOCAL_BOOTSTRAP", matches = "true")
@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MYSQL_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/td_issue32_bl01_http.*")
class BulkLedgerImportLocalMySqlBootstrapTest {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TD_ISSUE32_MYSQL_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("TD_ISSUE32_MYSQL_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("TD_ISSUE32_MYSQL_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Test
    void createsOnlyTheDisposableLocalSchema() { }
}
