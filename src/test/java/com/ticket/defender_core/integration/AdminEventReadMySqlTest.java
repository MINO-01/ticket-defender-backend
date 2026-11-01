package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.out.persistence.AdminMembershipRepository;
import com.ticket.defender_core.adapter.out.persistence.MembershipEventGrantRepository;
import com.ticket.defender_core.adapter.out.persistence.MembershipRoleRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantEventRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantRepository;
import com.ticket.defender_core.domain.AccessRecordStatus;
import com.ticket.defender_core.domain.AdminMembershipEntity;
import com.ticket.defender_core.domain.MembershipEventGrant;
import com.ticket.defender_core.domain.MembershipRole;
import com.ticket.defender_core.domain.Tenant;
import com.ticket.defender_core.domain.TenantEvent;
import com.ticket.defender_core.security.AdminRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.CannotCreateTransactionException;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 격리 MySQL 8 스키마에서 관리자 접근 가능 공연 목록의 활성 상태 필터, 공연 권한 격리, 다중 tenant 차단, 정렬과
 * DB 연결 실패 시 503 응답 및 복구 후 정상 조회, 다중 고객사 관리자의 업무 API 임시 차단을 검증합니다.
 * 운영 DB와 MinIO는 사용하지 않으며, 생성한 합성 행은 테스트 종료 시 삭제합니다.
 */
// 연결 장애 검증이 30초 기본 대기 시간에 묶이지 않도록 연결 획득 대기 시간만 줄입니다.
@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate",
        "spring.datasource.hikari.connection-timeout=2000"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MYSQL_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:13307/td_issue32.*")
class AdminEventReadMySqlTest {
    @Autowired private MockMvc mvc;
    @Autowired private TenantRepository tenants;
    @Autowired private TenantEventRepository events;
    @Autowired private AdminMembershipRepository memberships;
    @Autowired private MembershipRoleRepository roles;
    @Autowired private MembershipEventGrantRepository grants;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    @MockitoBean(name = "adminJwtDecoder")
    private JwtDecoder adminDecoder;

    private final List<UUID> tenantIds = new ArrayList<>();
    private String suffix;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TD_ISSUE32_MYSQL_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("TD_ISSUE32_MYSQL_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("TD_ISSUE32_MYSQL_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @BeforeEach
    void setUp() {
        // 서명 검증은 H2 API 테스트가 담당하며, 여기서는 검증된 subject만 가진 JWT로 MySQL 조회 범위를 확인합니다.
        given(adminDecoder.decode(anyString())).willAnswer(call -> Jwt.withTokenValue(call.getArgument(0))
                .header("alg", "RS256").subject(call.getArgument(0)).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300)).build());
        suffix = UUID.randomUUID().toString();
    }

    @AfterEach
    void cleanup() {
        for (UUID tenant : tenantIds) {
            jdbc.update("delete r from membership_roles r join memberships m on m.id = r.membership_id"
                    + " where m.tenant_id = ?", tenant.toString());
            jdbc.update("delete from membership_events where tenant_id = ?", tenant.toString());
            jdbc.update("delete from memberships where tenant_id = ?", tenant.toString());
            jdbc.update("delete from events where tenant_id = ?", tenant.toString());
            jdbc.update("delete from tenants where id = ?", tenant.toString());
        }
    }

    @Test
    void activeGrantedEventsAreScopedSortedAndInactiveRecordsAreExcluded() throws Exception {
        UUID tenantA = tenant(AccessRecordStatus.ACTIVE);
        UUID tenantB = tenant(AccessRecordStatus.ACTIVE);
        UUID sameNameHigh = event(tenantA, UUID.fromString("ffffffff-0000-4000-8000-" + tail()), "가 공연",
                AccessRecordStatus.ACTIVE);
        UUID sameNameLow = event(tenantA, UUID.fromString("00000000-0000-4000-8000-" + tail()), "가 공연",
                AccessRecordStatus.ACTIVE);
        UUID later = event(tenantA, UUID.randomUUID(), "나 공연", AccessRecordStatus.ACTIVE);
        UUID inactive = event(tenantA, UUID.randomUUID(), "다 비활성", AccessRecordStatus.INACTIVE);
        UUID ungranted = event(tenantA, UUID.randomUUID(), "라 권한 없음", AccessRecordStatus.ACTIVE);
        UUID otherTenantEvent = event(tenantB, UUID.randomUUID(), "고객사 B 공연", AccessRecordStatus.ACTIVE);
        String analyst = "mysql-event-analyst-" + suffix;
        member(analyst, tenantA, AccessRecordStatus.ACTIVE, AdminRole.ANALYST,
                List.of(later, sameNameHigh, sameNameLow, inactive));
        String reviewer = "mysql-event-reviewer-" + suffix;
        member(reviewer, tenantA, AccessRecordStatus.ACTIVE, AdminRole.REVIEWER, List.of(later));
        member("mysql-event-other-" + suffix, tenantB, AccessRecordStatus.ACTIVE, AdminRole.REVIEWER,
                List.of(otherTenantEvent));

        mvc.perform(get("/api/v1/admin/events").header(HttpHeaders.AUTHORIZATION, "Bearer " + analyst))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.items[0].id").value(sameNameLow.toString()))
                .andExpect(jsonPath("$.items[1].id").value(sameNameHigh.toString()))
                .andExpect(jsonPath("$.items[2].id").value(later.toString()))
                .andExpect(content().string(not(containsString(inactive.toString()))))
                .andExpect(content().string(not(containsString(ungranted.toString()))))
                .andExpect(content().string(not(containsString(otherTenantEvent.toString()))))
                .andExpect(content().string(not(containsString(tenantA.toString()))));
        mvc.perform(get("/api/v1/admin/events").param("pageSize", "1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.items[0].id").value(later.toString()));
    }

    @Test
    void multipleActiveTenantsAreBlockedWhileInactiveTenantsAndMembershipsAreIgnored() throws Exception {
        UUID tenantA = tenant(AccessRecordStatus.ACTIVE);
        UUID tenantB = tenant(AccessRecordStatus.ACTIVE);
        UUID tenantOff = tenant(AccessRecordStatus.INACTIVE);
        UUID eventA = event(tenantA, UUID.randomUUID(), "A 공연", AccessRecordStatus.ACTIVE);
        UUID eventB = event(tenantB, UUID.randomUUID(), "B 공연", AccessRecordStatus.ACTIVE);
        UUID eventOff = event(tenantOff, UUID.randomUUID(), "비활성 고객사 공연", AccessRecordStatus.ACTIVE);
        String multi = "mysql-event-multi-" + suffix;
        member(multi, tenantA, AccessRecordStatus.ACTIVE, AdminRole.ANALYST, List.of(eventA));
        member(multi, tenantB, AccessRecordStatus.ACTIVE, AdminRole.REVIEWER, List.of(eventB));
        String withInactive = "mysql-event-inactive-" + suffix;
        member(withInactive, tenantA, AccessRecordStatus.INACTIVE, AdminRole.ANALYST, List.of(eventA));
        member(withInactive, tenantOff, AccessRecordStatus.ACTIVE, AdminRole.ANALYST, List.of(eventOff));
        member(withInactive, tenantB, AccessRecordStatus.ACTIVE, AdminRole.APPROVER, List.of(eventB));
        String noRole = "mysql-event-no-role-" + suffix;
        member(noRole, tenantA, AccessRecordStatus.ACTIVE, null, List.of(eventA));

        mvc.perform(get("/api/v1/admin/events").header(HttpHeaders.AUTHORIZATION, "Bearer " + multi))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TENANT_SELECTION_REQUIRED"))
                .andExpect(content().string(not(containsString(eventA.toString()))))
                .andExpect(content().string(not(containsString(eventB.toString()))));
        mvc.perform(get("/api/v1/admin/events").header(HttpHeaders.AUTHORIZATION, "Bearer " + withInactive))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].id").value(eventB.toString()));
        mvc.perform(get("/api/v1/admin/events").header(HttpHeaders.AUTHORIZATION, "Bearer " + noRole))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/events")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer mysql-event-nobody-" + suffix))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void connectionFailureAtTransactionStartIsReported503AndRecoveryRestoresTheList() throws Exception {
        UUID tenant = tenant(AccessRecordStatus.ACTIVE);
        UUID eventId = event(tenant, UUID.randomUUID(), "장애 복구 공연", AccessRecordStatus.ACTIVE);
        String analyst = "mysql-event-outage-" + suffix;
        member(analyst, tenant, AccessRecordStatus.ACTIVE, AdminRole.ANALYST, List.of(eventId));
        String nobody = "mysql-event-outage-nobody-" + suffix;

        mvc.perform(get("/api/v1/admin/events").header(HttpHeaders.AUTHORIZATION, "Bearer " + nobody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0));

        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        String password = pool.getPassword();
        try {
            // 실제 MySQL이 새 연결을 거부하도록 접속 정보를 바꾸고 기존 연결을 비워 트랜잭션 시작 단계에서 실패시킵니다.
            pool.getHikariConfigMXBean().setPassword("synthetic-wrong-" + suffix);
            pool.getHikariPoolMXBean().softEvictConnections();
            for (String subject : List.of(analyst, nobody)) {
                var result = mvc.perform(get("/api/v1/admin/events")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + subject))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                        .andExpect(jsonPath("$.items").doesNotExist())
                        .andExpect(content().string(not(containsString("jdbc"))))
                        .andExpect(content().string(not(containsString("13307"))))
                        .andExpect(content().string(not(containsString("Access denied"))))
                        .andExpect(content().string(not(containsString(eventId.toString()))))
                        .andReturn();
                assertThat(result.getResolvedException()).isInstanceOf(CannotCreateTransactionException.class)
                        .hasRootCauseInstanceOf(SQLException.class);
            }
        } finally {
            pool.getHikariConfigMXBean().setPassword(password);
        }

        // 복구 직후에는 연결 풀의 재시도 대기 때문에 잠시 503이 이어질 수 있으나 200 빈 목록이나 500이 섞이면 안 됩니다.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        int recovered;
        do {
            var attempt = mvc.perform(get("/api/v1/admin/events")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + analyst)).andReturn().getResponse();
            recovered = attempt.getStatus();
            if (recovered == 503) {
                assertThat(attempt.getContentAsString()).contains("DEPENDENCY_UNAVAILABLE");
                Thread.sleep(250);
            }
        } while (recovered == 503 && System.nanoTime() < deadline);
        assertThat(recovered).isEqualTo(200);

        mvc.perform(get("/api/v1/admin/events").header(HttpHeaders.AUTHORIZATION, "Bearer " + analyst))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].id").value(eventId.toString()));
        mvc.perform(get("/api/v1/admin/events").header(HttpHeaders.AUTHORIZATION, "Bearer " + nobody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void multiTenantAdminsAreBlockedOnBusinessApisWhileInactiveAndCaseVariantRowsAreExcluded() throws Exception {
        UUID tenantA = tenant(AccessRecordStatus.ACTIVE);
        UUID tenantB = tenant(AccessRecordStatus.ACTIVE);
        UUID tenantOff = tenant(AccessRecordStatus.INACTIVE);
        UUID eventA = event(tenantA, UUID.randomUUID(), "A 공연", AccessRecordStatus.ACTIVE);
        UUID eventB = event(tenantB, UUID.randomUUID(), "B 공연", AccessRecordStatus.ACTIVE);
        String multi = "mysql-guard-multi-" + suffix;
        member(multi, tenantA, AccessRecordStatus.ACTIVE, AdminRole.ANALYST, List.of(eventA));
        member(multi, tenantB, AccessRecordStatus.ACTIVE, AdminRole.REVIEWER, List.of(eventB));
        String excluded = "mysql-guard-excluded-" + suffix;
        member(excluded, tenantA, AccessRecordStatus.ACTIVE, AdminRole.ANALYST, List.of(eventA));
        member(excluded, tenantB, AccessRecordStatus.INACTIVE, AdminRole.ANALYST, List.of(eventB));
        member(excluded, tenantOff, AccessRecordStatus.ACTIVE, AdminRole.ANALYST, List.of());
        // MySQL 기본 collation에서 대소문자만 다른 subject는 같은 관리자가 아니므로 고객사 수에 넣지 않습니다.
        String exact = "mysql-guard-case-" + suffix;
        member(exact, tenantA, AccessRecordStatus.ACTIVE, AdminRole.ANALYST, List.of(eventA));
        member(exact.toUpperCase(), tenantB, AccessRecordStatus.ACTIVE, AdminRole.ANALYST, List.of(eventB));

        for (var request : List.of(
                get("/api/v1/admin/events"),
                get("/api/v1/admin/track1/clusters").param("eventId", eventA.toString()),
                get("/api/v1/admin/track1/clusters/detail").param("eventId", eventA.toString())
                        .param("analysisRequestId", "run-" + suffix).param("clusterId", "cluster-1"),
                get("/api/v1/admin/fan-reports").param("eventId", eventB.toString()),
                get("/api/v1/cases").param("eventId", eventB.toString()))) {
            mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + multi))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TENANT_SELECTION_REQUIRED"))
                    .andExpect(content().string(not(containsString(tenantA.toString()))))
                    .andExpect(content().string(not(containsString(tenantB.toString()))))
                    .andExpect(content().string(not(containsString("A 공연"))));
        }
        // 공연 권한이 없는 다른 고객사 공연과 없는 공연은 409 대신 기존 404로 숨깁니다.
        mvc.perform(get("/api/v1/admin/track1/clusters").param("eventId", UUID.randomUUID().toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + multi))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/track1/clusters").param("eventId", eventB.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + excluded))
                .andExpect(status().isNotFound());

        for (String single : List.of(excluded, exact)) {
            mvc.perform(get("/api/v1/admin/track1/clusters").param("eventId", eventA.toString())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + single))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.event.id").value(eventA.toString()));
            mvc.perform(get("/api/v1/admin/events").header(HttpHeaders.AUTHORIZATION, "Bearer " + single))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalItems").value(1))
                    .andExpect(jsonPath("$.items[0].id").value(eventA.toString()));
        }
    }

    private String tail() {
        return suffix.substring(24);
    }

    private UUID tenant(AccessRecordStatus status) {
        UUID id = UUID.randomUUID();
        tenants.saveAndFlush(new Tenant(id, "MySQL 합성 고객사", status));
        tenantIds.add(id);
        return id;
    }

    private UUID event(UUID tenant, UUID id, String name, AccessRecordStatus status) {
        events.saveAndFlush(new TenantEvent(id, tenant, "ext-" + id, name, status));
        return id;
    }

    private void member(String subject, UUID tenant, AccessRecordStatus status, AdminRole role, List<UUID> eventIds) {
        UUID membership = UUID.randomUUID();
        memberships.saveAndFlush(new AdminMembershipEntity(membership, tenant, subject, status));
        if (role != null) roles.saveAndFlush(new MembershipRole(UUID.randomUUID(), membership, role));
        eventIds.forEach(event -> grants.saveAndFlush(
                new MembershipEventGrant(UUID.randomUUID(), membership, tenant, event)));
    }
}
