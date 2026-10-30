package com.ticket.defender_core.integration;

import com.ticket.defender_core.domain.ApprovalDecision;
import com.ticket.defender_core.domain.ReviewReceipt;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalReviewDecisionIntegrationTest {
    @Test
    void reviewReceiptAndDecisionFoundationDefineOnlyOneTimeReviewStates() {
        assertThat(ReviewReceipt.Status.values()).containsExactly(
                ReviewReceipt.Status.ISSUED,
                ReviewReceipt.Status.CONSUMED,
                ReviewReceipt.Status.REVOKED,
                ReviewReceipt.Status.EXPIRED);
        assertThat(ApprovalDecision.Outcome.values()).containsExactly(
                ApprovalDecision.Outcome.APPROVE,
                ApprovalDecision.Outcome.REJECT);
    }
}
