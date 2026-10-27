package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.out.persistence.EvidenceVerificationRecordRepository;
import com.ticket.defender_core.domain.EvidenceVerificationRecord;
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
class CaseW06SchemaValidateMySqlTest {
    @Autowired private EvidenceVerificationRecordRepository records;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TD_ISSUE32_MYSQL_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("TD_ISSUE32_MYSQL_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("TD_ISSUE32_MYSQL_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Test
    void validatesJpaMappingAndReadsOneMigratedScopedRecord() {
        EvidenceVerificationRecord record = records.findById(UUID.fromString(CaseW06MigrationMySqlTest.RECORD_ID))
                .orElseThrow();
        assertThat(record.getVerificationStatus()).isEqualTo(EvidenceVerificationRecord.Status.MATCH);
        assertThat(record.getPreservationStatus()).isEqualTo(EvidenceVerificationRecord.Preservation.UNPROTECTED);
        assertThat(record.getTenantId()).isNotNull();
        assertThat(record.getEventId()).isNotNull();
        assertThat(record.getEvidenceSnapshotId()).isNotNull();
    }
}
