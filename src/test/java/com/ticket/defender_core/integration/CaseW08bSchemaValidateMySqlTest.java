package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.out.persistence.ApprovalDecisionRepository;
import com.ticket.defender_core.adapter.out.persistence.ApprovalRequestRepository;
import com.ticket.defender_core.adapter.out.persistence.ReviewReceiptRepository;
import com.ticket.defender_core.domain.ApprovalDecision;
import com.ticket.defender_core.domain.ReviewReceipt;
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
class CaseW08bSchemaValidateMySqlTest {
    @Autowired private ReviewReceiptRepository receipts;
    @Autowired private ApprovalDecisionRepository decisions;
    @Autowired private ApprovalRequestRepository requests;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TD_ISSUE32_MYSQL_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("TD_ISSUE32_MYSQL_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("TD_ISSUE32_MYSQL_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Test
    void validatesJpaMappingsAndReadsOneTimeReceiptAndImmutableDecision() {
        ReviewReceipt receipt = receipts.findById(UUID.fromString(CaseW08bMigrationMySqlTest.RECEIPT_ID))
                .orElseThrow();
        ApprovalDecision decision = decisions.findById(UUID.fromString(CaseW08bMigrationMySqlTest.DECISION_ID))
                .orElseThrow();

        assertThat(receipt.getStatus()).isEqualTo(ReviewReceipt.Status.CONSUMED);
        assertThat(receipt.getActiveApprovalRequestId()).isNull();
        assertThat(receipt.getTokenSha256()).isEqualTo(CaseW08bMigrationMySqlTest.RECEIPT_TOKEN_SHA256);
        assertThat(decision.getOutcome()).isEqualTo(ApprovalDecision.Outcome.APPROVE);
        assertThat(decision.getReviewReceiptId()).isEqualTo(receipt.getId());
        assertThat(decision.getApprovalRequestId()).isEqualTo(
                UUID.fromString(CaseW08aMigrationMySqlTest.REQUEST_ID));
        assertThat(requests.findById(decision.getApprovalRequestId())).isPresent();
    }
}
