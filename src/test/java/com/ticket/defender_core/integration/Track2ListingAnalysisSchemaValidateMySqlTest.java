package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.out.persistence.ListingContextAnalysisRepository;
import com.ticket.defender_core.adapter.out.persistence.ListingContextObservationRepository;
import com.ticket.defender_core.domain.ListingContextAnalysis;
import com.ticket.defender_core.domain.ListingContextObservation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MYSQL_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:13307/td_issue32.*")
class Track2ListingAnalysisSchemaValidateMySqlTest {
    @Autowired private ListingContextAnalysisRepository analyses;
    @Autowired private ListingContextObservationRepository observations;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TD_ISSUE32_MYSQL_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("TD_ISSUE32_MYSQL_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("TD_ISSUE32_MYSQL_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Test
    void validatesJpaMappingsForAnalysisAndFieldObservations() {
        UUID id = UUID.fromString(Track2ListingAnalysisMigrationMySqlTest.ANALYSIS_ID);
        ListingContextAnalysis analysis = analyses.findById(id).orElseThrow();
        assertThat(analysis.getExecutionStatus()).isEqualTo(ListingContextAnalysis.Execution.SUCCEEDED);
        assertThat(analysis.getAnalysisStatus()).isEqualTo(ListingContextAnalysis.AnalysisStatus.COMPLETED);
        assertThat(analysis.getReviewRequired()).isTrue();
        assertThat(analysis.getActiveEvidenceId()).isNull();
        List<ListingContextObservation> stored = observations.findByAnalysisIdIn(List.of(id));
        assertThat(stored).hasSize(4);
        assertThat(stored).anySatisfy(item -> {
            assertThat(item.getFieldName()).isEqualTo(ListingContextObservation.Field.zone);
            assertThat(item.candidates()).containsExactly("A", "B");
        });
        assertThat(stored).anySatisfy(item -> {
            assertThat(item.getFieldName()).isEqualTo(ListingContextObservation.Field.askingPrice);
            assertThat(item.getPriceAmount()).isEqualTo("450000");
        });
        assertThat(stored).anySatisfy(item -> assertThat(item.getUnknownReason())
                .isEqualTo(ListingContextObservation.UnknownReason.ABSENT));
    }
}
