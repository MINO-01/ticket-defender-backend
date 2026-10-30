package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.out.persistence.OfficialPdfArtifactRepository;
import com.ticket.defender_core.adapter.out.persistence.OfficialPdfIssuanceAttemptRepository;
import com.ticket.defender_core.adapter.out.persistence.OfficialPdfIssuanceRepository;
import com.ticket.defender_core.domain.OfficialPdfIssuance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MYSQL_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:13307/td_issue32.*")
class CaseW09SchemaValidateMySqlTest {
    @Autowired private OfficialPdfIssuanceRepository issuances;
    @Autowired private OfficialPdfArtifactRepository artifacts;
    @Autowired private OfficialPdfIssuanceAttemptRepository attempts;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TD_ISSUE32_MYSQL_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("TD_ISSUE32_MYSQL_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("TD_ISSUE32_MYSQL_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Test
    void validatesJpaMappingsForSeparateGeneratedArtifactAndRetryHistory() {
        UUID issuanceId = UUID.fromString(CaseW09MigrationMySqlTest.ISSUANCE_ID);
        OfficialPdfIssuance issuance = issuances.findById(issuanceId).orElseThrow();
        assertThat(issuance.getStatus()).isEqualTo(OfficialPdfIssuance.Status.GENERATED);
        assertThat(issuance.getCandidateObjectVersion()).isEqualTo("synthetic-version-1");
        assertThat(artifacts.findByIssuanceId(issuanceId)).hasValueSatisfying(artifact -> {
            assertThat(artifact.getId()).isEqualTo(UUID.fromString(CaseW09MigrationMySqlTest.ARTIFACT_ID));
            assertThat(artifact.getApprovalDecisionId()).isEqualTo(issuance.getApprovalDecisionId());
            assertThat(artifact.getObjectVersion()).isEqualTo(issuance.getCandidateObjectVersion());
        });
        assertThat(attempts.findByIssuanceIdOrderByAttemptNumberAsc(issuanceId))
                .singleElement().extracting(attempt -> attempt.getAttemptNumber()).isEqualTo(1);
    }
}
