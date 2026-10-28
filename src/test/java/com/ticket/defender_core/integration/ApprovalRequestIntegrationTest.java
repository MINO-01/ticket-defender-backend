package com.ticket.defender_core.integration;

import com.ticket.defender_core.application.service.ReportTemplateRegistry;
import com.ticket.defender_core.domain.ApprovalRequest;
import com.ticket.defender_core.domain.ReportVersion;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalRequestIntegrationTest {
    @Test
    void approvalRequestFoundationDefinesPendingBindingAndRetainedTemplate() {
        assertThat(ApprovalRequest.Status.values()).containsExactly(
                ApprovalRequest.Status.PENDING,
                ApprovalRequest.Status.APPROVED,
                ApprovalRequest.Status.REJECTED,
                ApprovalRequest.Status.WITHDRAWN,
                ApprovalRequest.Status.INVALIDATED);
        assertThat(ReportVersion.Status.values()).contains(ReportVersion.Status.PENDING_APPROVAL);
        assertThat(ReportTemplateRegistry.CURRENT_VERSION).isEqualTo("case-approval-input-v1");
    }
}
