package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.out.persistence.EvidencePreservationAttemptRepository;
import com.ticket.defender_core.adapter.out.persistence.EvidencePreservationPolicyRepository;
import com.ticket.defender_core.adapter.out.persistence.EvidencePreservationRecordRepository;
import com.ticket.defender_core.domain.EvidencePreservationAttempt;
import com.ticket.defender_core.domain.EvidencePreservationPolicy;
import com.ticket.defender_core.domain.EvidencePreservationRecord;
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
class CaseW07SchemaValidateMySqlTest {
    @Autowired private EvidencePreservationPolicyRepository policies;
    @Autowired private EvidencePreservationRecordRepository records;
    @Autowired private EvidencePreservationAttemptRepository attempts;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TD_ISSUE32_MYSQL_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("TD_ISSUE32_MYSQL_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("TD_ISSUE32_MYSQL_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Test
    void validatesJpaMappingsAndReadsVersionedPolicyRecordAndAttempt() {
        EvidencePreservationPolicy policy = policies.findById(
                UUID.fromString(CaseW07MigrationMySqlTest.POLICY_ROW_ID)).orElseThrow();
        EvidencePreservationRecord record = records.findById(
                UUID.fromString(CaseW07MigrationMySqlTest.RECORD_ID)).orElseThrow();
        EvidencePreservationAttempt attempt = attempts.findById(
                UUID.fromString(CaseW07MigrationMySqlTest.ATTEMPT_ID)).orElseThrow();

        assertThat(policy.getPolicyStatus()).isEqualTo(EvidencePreservationPolicy.Status.APPROVED);
        assertThat(policy.getPreservationMode()).isEqualTo(EvidencePreservationPolicy.Mode.GOVERNANCE);
        assertThat(record.getPreservationPolicyId()).isEqualTo(policy.getPolicyId());
        assertThat(record.getPreservationPolicyVersion()).isEqualTo(1);
        assertThat(record.getPreservationStatus()).isEqualTo(
                EvidencePreservationRecord.Status.IMMUTABLE_VERIFIED);
        assertThat(record.getActualVersionId()).isEqualTo("locked-version-1");
        assertThat(attempt.getPreservationRecordId()).isEqualTo(record.getId());
        assertThat(attempt.getOutcome()).isEqualTo(EvidencePreservationAttempt.Outcome.VERIFIED);
    }
}
