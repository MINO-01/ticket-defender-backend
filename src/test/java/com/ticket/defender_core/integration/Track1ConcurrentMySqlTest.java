package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.in.web.dto.AgentAnalysisRequest;
import com.ticket.defender_core.adapter.out.api.FastApiAdapter;
import com.ticket.defender_core.adapter.out.api.dto.FastApiClusterResponse;
import com.ticket.defender_core.adapter.out.api.dto.MacroAnalysisRequest;
import com.ticket.defender_core.adapter.out.api.dto.MacroAnalysisResponse;
import com.ticket.defender_core.adapter.out.persistence.MacroAuditEvidenceRepository;
import com.ticket.defender_core.adapter.out.persistence.ServiceTenantBindingRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantEventRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantRepository;
import com.ticket.defender_core.application.service.FraudAnalysisService;
import com.ticket.defender_core.application.service.FraudAnalysisStatus;
import com.ticket.defender_core.domain.AccessRecordStatus;
import com.ticket.defender_core.domain.ServiceTenantBinding;
import com.ticket.defender_core.domain.Tenant;
import com.ticket.defender_core.domain.TenantEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.show-sql=false"
})
@EnabledIfEnvironmentVariable(named = "TD_MYSQL_MIGRATION_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:[1-9][0-9]*/.*")
class Track1ConcurrentMySqlTest {
    @Autowired private TenantRepository tenants;
    @Autowired private TenantEventRepository events;
    @Autowired private ServiceTenantBindingRepository bindings;
    @Autowired private MacroAuditEvidenceRepository evidence;
    @Autowired private FraudAnalysisService analysis;

    @MockitoBean
    private FastApiAdapter fastApiAdapter;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TD_MYSQL_MIGRATION_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("TD_MYSQL_MIGRATION_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("TD_MYSQL_MIGRATION_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Test
    void simultaneousRequestsStoreOneEvidencePerMember() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String subject = "ingest-" + UUID.randomUUID();
        String externalEventRef = "event-" + UUID.randomUUID();
        tenants.saveAndFlush(new Tenant(tenantId, "동시성 고객사", AccessRecordStatus.ACTIVE));
        events.saveAndFlush(new TenantEvent(eventId, tenantId, externalEventRef,
                "동시성 공연", AccessRecordStatus.ACTIVE));
        bindings.saveAndFlush(new ServiceTenantBinding(UUID.randomUUID(),
                "https://issuer.example.test/service", "trusted-ingest", subject,
                tenantId, AccessRecordStatus.ACTIVE));

        AgentAnalysisRequest request = new AgentAnalysisRequest(List.of(
                new AgentAnalysisRequest.TicketData("account-1", "reservation-1", externalEventRef,
                        "a".repeat(64), null, null, null),
                new AgentAnalysisRequest.TicketData("account-2", "reservation-2", externalEventRef,
                        "a".repeat(64), null, null, null)));
        CountDownLatch analyzed = new CountDownLatch(2);
        given(fastApiAdapter.requestMacroAnalysis(any(MacroAnalysisRequest.class))).willAnswer(call -> {
            MacroAnalysisRequest sent = call.getArgument(0);
            analyzed.countDown();
            if (!analyzed.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시 요청이 분석 단계에 도달하지 못했습니다.");
            }
            FastApiClusterResponse cluster = new FastApiClusterResponse("cluster-1", List.of(
                    new FastApiClusterResponse.Member("account-1", "reservation-1", externalEventRef),
                    new FastApiClusterResponse.Member("account-2", "reservation-2", externalEventRef)),
                    List.of("a".repeat(64)), List.of(), List.of(), List.of(), 0.81);
            return new MacroAnalysisResponse(sent.requestId(), MacroAnalysisResponse.Status.COMPLETED,
                    "LOUVAIN", "1.0.0", OffsetDateTime.parse("2026-10-08T10:15:30Z"), List.of(cluster));
        });

        Callable<FraudAnalysisStatus> task = () -> {
            Jwt jwt = Jwt.withTokenValue("test-service-token")
                    .header("alg", "RS256")
                    .issuer("https://issuer.example.test/service")
                    .subject(subject)
                    .claim("client_id", "trusted-ingest")
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(300))
                    .build();
            JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt);
            authentication.setAuthenticated(true);
            SecurityContextHolder.getContext().setAuthentication(authentication);
            try {
                return analysis.processAgentData(request);
            } finally {
                SecurityContextHolder.clearContext();
            }
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<FraudAnalysisStatus> first = executor.submit(task);
            Future<FraudAnalysisStatus> second = executor.submit(task);
            assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(FraudAnalysisStatus.COMPLETED);
            assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo(FraudAnalysisStatus.COMPLETED);
        } finally {
            executor.shutdownNow();
        }
        assertThat(evidence.findAllByTenantIdAndInternalEventId(tenantId, eventId)).hasSize(2);
    }
}
