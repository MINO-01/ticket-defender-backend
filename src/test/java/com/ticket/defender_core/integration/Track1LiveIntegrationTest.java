package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.in.web.dto.AgentAnalysisRequest;
import com.ticket.defender_core.adapter.out.persistence.MacroAuditEvidenceRepository;
import com.ticket.defender_core.adapter.out.persistence.TicketAuditRepository;
import com.ticket.defender_core.adapter.out.persistence.ServiceTenantBindingRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantEventRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantRepository;
import com.ticket.defender_core.application.service.FraudAnalysisService;
import com.ticket.defender_core.application.service.FraudAnalysisStatus;
import com.ticket.defender_core.domain.AuditStatus;
import com.ticket.defender_core.domain.EvidenceType;
import com.ticket.defender_core.domain.MacroAuditEvidence;
import com.ticket.defender_core.domain.TicketAudit;
import com.ticket.defender_core.domain.AccessRecordStatus;
import com.ticket.defender_core.domain.ServiceTenantBinding;
import com.ticket.defender_core.domain.Tenant;
import com.ticket.defender_core.domain.TenantEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;
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

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID EVENT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Autowired
    private FraudAnalysisService fraudAnalysisService;

    @Autowired
    private TicketAuditRepository ticketAuditRepository;

    @Autowired
    private MacroAuditEvidenceRepository macroAuditEvidenceRepository;

    @Autowired
    private TenantRepository tenants;

    @Autowired
    private TenantEventRepository events;

    @Autowired
    private ServiceTenantBindingRepository serviceBindings;

    @BeforeEach
    void seedIngestScope() {
        tenants.save(new Tenant(TENANT_ID, "테스트 고객사", AccessRecordStatus.ACTIVE));
        events.save(new TenantEvent(EVENT_ID, TENANT_ID, "event-1", "테스트 공연", AccessRecordStatus.ACTIVE));
        serviceBindings.save(new ServiceTenantBinding(UUID.randomUUID(), "https://issuer.example.test/service",
                "trusted-ingest", "ingest-1", TENANT_ID, AccessRecordStatus.ACTIVE));
        Jwt jwt = Jwt.withTokenValue("test-service-token")
                .header("alg", "RS256")
                .issuer("https://issuer.example.test/service")
                .subject("ingest-1")
                .claim("client_id", "trusted-ingest")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt);
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void clearIdentity() {
        SecurityContextHolder.clearContext();
    }

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
        assertThat(evidence).allSatisfy(item -> {
            assertThat(item.getTenantId()).isEqualTo(TENANT_ID);
            assertThat(item.getInternalEventId()).isEqualTo(EVENT_ID);
        });
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
