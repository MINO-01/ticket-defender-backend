package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.out.persistence.ApprovalAuditEventRepository;
import com.ticket.defender_core.adapter.out.persistence.ApprovalRequestRepository;
import com.ticket.defender_core.adapter.out.persistence.CaseRepository;
import com.ticket.defender_core.adapter.out.persistence.IssuanceHoldRepository;
import com.ticket.defender_core.adapter.out.persistence.ReportVersionRepository;
import com.ticket.defender_core.domain.ApprovalRequest;
import com.ticket.defender_core.domain.IssuanceHold;
import com.ticket.defender_core.domain.ReportVersion;
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
class CaseW08aSchemaValidateMySqlTest {
    @Autowired private ApprovalRequestRepository requests;
    @Autowired private ApprovalAuditEventRepository audits;
    @Autowired private IssuanceHoldRepository holds;
    @Autowired private ReportVersionRepository reports;
    @Autowired private CaseRepository cases;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TD_ISSUE32_MYSQL_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("TD_ISSUE32_MYSQL_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("TD_ISSUE32_MYSQL_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Test
    void validatesJpaMappingsAndReadsPinnedApprovalRequestAuditAndHold() {
        ApprovalRequest request = requests.findById(
                UUID.fromString(CaseW08aMigrationMySqlTest.REQUEST_ID)).orElseThrow();
        ReportVersion report = reports.findById(request.getReportVersionId()).orElseThrow();
        IssuanceHold hold = holds.findById(
                UUID.fromString(CaseW08aMigrationMySqlTest.HOLD_ID)).orElseThrow();

        assertThat(request.getStatus()).isEqualTo(ApprovalRequest.Status.PENDING);
        assertThat(request.getReportTemplateSha256()).isEqualTo(CaseW08aMigrationMySqlTest.TEMPLATE_SHA256);
        assertThat(request.getVersion()).isZero();
        assertThat(report.getStatus()).isEqualTo(ReportVersion.Status.PENDING_APPROVAL.name());
        assertThat(report.getTemplateSha256()).isEqualTo(CaseW08aMigrationMySqlTest.TEMPLATE_SHA256);
        assertThat(cases.findById(request.getCaseId()).orElseThrow().getCurrentReportVersionId())
                .isEqualTo(report.getId());
        assertThat(audits.findByApprovalRequestIdOrderByOccurredAtAscIdAsc(request.getId()))
                .singleElement().extracting(event -> event.getId())
                .isEqualTo(UUID.fromString(CaseW08aMigrationMySqlTest.AUDIT_ID));
        assertThat(hold.getStatus()).isEqualTo(IssuanceHold.Status.ACTIVE);
    }
}
