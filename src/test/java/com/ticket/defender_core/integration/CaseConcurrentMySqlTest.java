package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.in.web.dto.CaseDto;
import com.ticket.defender_core.adapter.out.persistence.*;
import com.ticket.defender_core.application.service.CaseApiException;
import com.ticket.defender_core.application.service.CaseService;
import com.ticket.defender_core.domain.*;
import com.ticket.defender_core.security.AdminRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs after CaseMigrationMySqlTest on the dedicated disposable MySQL 8 CI schema. */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MYSQL_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:13307/td_issue32.*")
class CaseConcurrentMySqlTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID EVENT = UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Autowired private CaseService service;
    @Autowired private CaseRepository cases;
    @Autowired private CaseActivityRepository activities;
    @Autowired private FanReportRepository reports;
    @Autowired private AdminMembershipRepository memberships;
    @Autowired private MembershipRoleRepository roles;
    @Autowired private MembershipEventGrantRepository grants;
    @Autowired private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TD_ISSUE32_MYSQL_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("TD_ISSUE32_MYSQL_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("TD_ISSUE32_MYSQL_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Test
    void concurrentTransitionsAllowOnlyOneCommittedActivity() throws Exception {
        Fixture fixture = createFixture();
        String subject = fixture.subject();
        CaseDto.View created = fixture.created();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try (var workers = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2; i++) {
                results.add(workers.submit(() -> {
                    authenticate(subject);
                    ready.countDown();
                    start.await();
                    try {
                        service.transition(created.caseId(), triage(created), UUID.randomUUID().toString());
                        return "SUCCESS";
                    } catch (CaseApiException conflict) {
                        return conflict.code();
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> result : results) outcomes.add(result.get(30, TimeUnit.SECONDS));
            assertThat(outcomes).containsExactlyInAnyOrder("SUCCESS", "CASE_VERSION_CONFLICT");
        }
        assertThat(cases.findById(created.caseId()).orElseThrow().getStatus()).isEqualTo(Case.Status.TRIAGED);
        assertThat(activities.findByCaseId(created.caseId(),
                org.springframework.data.domain.Pageable.unpaged()).stream()
                .filter(activity -> activity.getEventType().equals("CASE_TRIAGED"))).hasSize(1);
    }

    @Test
    void activityInsertFailureRollsBackStatusAndVersion() {
        Fixture fixture = createFixture();
        CaseDto.View created = fixture.created();
        String trigger = "td_w04_activity_failure";
        jdbc.execute("DROP TRIGGER IF EXISTS " + trigger);
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON case_activities FOR EACH ROW "
                + "BEGIN IF NEW.case_id = '" + created.caseId() + "' "
                + "AND NEW.event_type = 'CASE_TRIAGED' THEN "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'synthetic activity failure'; "
                + "END IF; END");
        try {
            assertThatThrownBy(() -> service.transition(created.caseId(), triage(created),
                    UUID.randomUUID().toString())).isInstanceOf(RuntimeException.class);
            Case unchanged = cases.findById(created.caseId()).orElseThrow();
            assertThat(unchanged.getStatus()).isEqualTo(Case.Status.OPEN);
            assertThat(unchanged.getCaseVersion()).isEqualTo(created.caseVersion());
            assertThat(activities.findByCaseId(created.caseId(),
                    org.springframework.data.domain.Pageable.unpaged()).getTotalElements()).isEqualTo(2);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS " + trigger);
        }
    }

    private Fixture createFixture() {
        UUID memberId = UUID.randomUUID();
        String subject = "case-reviewer-" + memberId;
        memberships.saveAndFlush(new AdminMembershipEntity(memberId, TENANT, subject, AccessRecordStatus.ACTIVE));
        roles.saveAndFlush(new MembershipRole(UUID.randomUUID(), memberId, AdminRole.REVIEWER));
        grants.saveAndFlush(new MembershipEventGrant(UUID.randomUUID(), memberId, TENANT, EVENT));
        UUID reportId = UUID.randomUUID();
        FanReport report = new FanReport(reportId, "FR-" + reportId, TENANT, EVENT,
                LocalDateTime.now().plusDays(1), null, FanReport.ReportType.OTHER,
                "Synthetic MySQL concurrency report", null, null, null, null, LocalDateTime.now(),
                UUID.randomUUID().toString(), "d".repeat(64), "e".repeat(64));
        report.markReceived();
        reports.saveAndFlush(report);
        authenticate(subject);
        CaseDto.View created = service.create(new CaseDto.Create(EVENT, Case.Type.FAN_REPORT,
                List.of(), List.of(reportId), List.of(), "Investigate concurrent transition", null, null),
                UUID.randomUUID().toString());
        return new Fixture(created, subject);
    }

    private static CaseDto.Transition triage(CaseDto.View created) {
        return new CaseDto.Transition(Case.Status.TRIAGED,
                created.caseVersion(), "Classify synthetic report", "Review listing",
                "Awaiting assignment", null, null, null, null);
    }

    private record Fixture(CaseDto.View created, String subject) { }

    private static void authenticate(String subject) {
        Jwt jwt = Jwt.withTokenValue("synthetic").header("alg", "RS256").subject(subject).build();
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt);
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
