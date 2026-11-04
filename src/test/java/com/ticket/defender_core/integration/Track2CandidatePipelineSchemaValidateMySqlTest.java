package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.out.persistence.ListingCandidateMatchRunRepository;
import com.ticket.defender_core.adapter.out.persistence.ReservationDataSourceRepository;
import com.ticket.defender_core.adapter.out.persistence.ReservationImportBatchRepository;
import com.ticket.defender_core.adapter.out.persistence.VerifiedPerformanceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MYSQL_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:13307/td_issue32.*")
class Track2CandidatePipelineSchemaValidateMySqlTest {
    @Autowired private VerifiedPerformanceRepository performances;
    @Autowired private ReservationImportBatchRepository batches;
    @Autowired private ReservationDataSourceRepository sources;
    @Autowired private ListingCandidateMatchRunRepository runs;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TD_ISSUE32_MYSQL_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("TD_ISSUE32_MYSQL_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("TD_ISSUE32_MYSQL_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Test
    void validatesCandidatePipelineJpaMappings() {
        assertThat(performances.count()).isGreaterThanOrEqualTo(0);
        assertThat(batches.count()).isGreaterThanOrEqualTo(0);
        assertThat(sources.count()).isGreaterThanOrEqualTo(0);
        assertThat(runs.count()).isGreaterThanOrEqualTo(0);
    }
}
