package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.in.web.dto.AgentAnalysisRequest;
import com.ticket.defender_core.adapter.out.persistence.MacroAuditEvidenceRepository;
import com.ticket.defender_core.adapter.out.persistence.TicketAuditRepository;
import com.ticket.defender_core.application.service.FraudAnalysisService;
import com.ticket.defender_core.application.service.FraudAnalysisStatus;
import com.ticket.defender_core.domain.AuditStatus;
import com.ticket.defender_core.domain.EvidenceType;
import com.ticket.defender_core.domain.MacroAuditEvidence;
import com.ticket.defender_core.domain.TicketAudit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:track1-live;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"
})
@EnabledIfEnvironmentVariable(named = "TD_TRACK1_FASTAPI_URL", matches = "http://127\\.0\\.0\\.1:[1-9][0-9]*")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class Track1LiveIntegrationTest {

    @Autowired
    private FraudAnalysisService fraudAnalysisService;

    @Autowired
    private TicketAuditRepository ticketAuditRepository;

    @Autowired
    private MacroAuditEvidenceRepository macroAuditEvidenceRepository;

    @Test
    void realLouvainResponseIsMappedAndSavedOnlyOnce() {
        AgentAnalysisRequest request = request("a".repeat(64));

        assertThat(fraudAnalysisService.processAgentData(request)).isEqualTo(FraudAnalysisStatus.COMPLETED);
        List<TicketAudit> audits = ticketAuditRepository.findAll();
        List<MacroAuditEvidence> evidence = macroAuditEvidenceRepository.findAll();
        assertThat(audits).hasSize(2);
        assertThat(evidence).hasSize(2);
        assertThat(audits).extracting(TicketAudit::getAccountId).containsExactlyInAnyOrder("uid-1", "uid-2");
        assertThat(audits).extracting(TicketAudit::getReservationNo)
                .containsExactlyInAnyOrder("res-1", "res-2");
        assertThat(audits).allSatisfy(audit -> {
            assertThat(audit.getEvidenceType()).isEqualTo(EvidenceType.MACRO_GRAPH);
            assertThat(audit.getStatus()).isEqualTo(AuditStatus.FRAUD_DETECTED);
        });
        assertThat(evidence).extracting(MacroAuditEvidence::getEventId)
                .containsExactly("event-1", "event-1");
        assertThat(evidence).extracting(MacroAuditEvidence::getClusterId)
                .doesNotContainNull().containsOnly(evidence.getFirst().getClusterId());
        assertThat(evidence.getFirst().getClusterId()).startsWith("cluster-");
        assertThat(evidence).allSatisfy(item -> {
            assertThat(item.getRiskScore()).isBetween(0.0, 1.0).isPositive();
            assertThat(item.getAnalysisAlgorithm()).isEqualTo("LOUVAIN");
            assertThat(item.getAnalysisAlgorithmVersion()).contains("gds-2.6.6");
            assertThat(item.getAnalysisCompletedAt()).isNotNull();
            assertThat(item.getAnalysisRequestId()).isEqualTo(evidence.getFirst().getAnalysisRequestId());
        });
        UUID.fromString(evidence.getFirst().getAnalysisRequestId());
        Set<String> memberKeys = evidence.stream()
                .map(item -> item.getAccountId() + "/" + item.getReservationNo() + "/" + item.getEventId())
                .collect(Collectors.toSet());
        assertThat(memberKeys).containsExactlyInAnyOrder("uid-1/res-1/event-1", "uid-2/res-2/event-1");

        assertThat(fraudAnalysisService.processAgentData(request)).isEqualTo(FraudAnalysisStatus.COMPLETED);
        assertThat(ticketAuditRepository.count()).isEqualTo(2);
        assertThat(macroAuditEvidenceRepository.count()).isEqualTo(2);

        AgentAnalysisRequest isolatedTicket = new AgentAnalysisRequest(List.of(
                new AgentAnalysisRequest.TicketData("uid-4", "res-4", "event-1",
                        "d".repeat(64), null, null, null)));
        assertThat(fraudAnalysisService.processAgentData(isolatedTicket))
                .isEqualTo(FraudAnalysisStatus.COMPLETED);
        assertThat(ticketAuditRepository.count()).isEqualTo(2);
    }

    @Test
    void fastApiValidationFailureDoesNotSaveAnAudit() {
        assertThat(fraudAnalysisService.processAgentData(request("invalid-token")))
                .isEqualTo(FraudAnalysisStatus.UNAVAILABLE);
        assertThat(ticketAuditRepository.count()).isZero();
        assertThat(macroAuditEvidenceRepository.count()).isZero();
    }

    private AgentAnalysisRequest request(String sharedPaymentHash) {
        return new AgentAnalysisRequest(List.of(
                new AgentAnalysisRequest.TicketData("uid-1", "res-1", "event-1",
                        sharedPaymentHash, null, null, null),
                new AgentAnalysisRequest.TicketData("uid-2", "res-2", "event-1",
                        sharedPaymentHash, null, null, null),
                new AgentAnalysisRequest.TicketData("uid-3", "res-3", "event-1",
                        "c".repeat(64), null, null, null)
        ));
    }
}
