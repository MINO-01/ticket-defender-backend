package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.out.persistence.AdminMembershipRepository;
import com.ticket.defender_core.adapter.out.persistence.EvidenceAssetRepository;
import com.ticket.defender_core.adapter.out.persistence.FanReportRepository;
import com.ticket.defender_core.adapter.out.persistence.MembershipEventGrantRepository;
import com.ticket.defender_core.adapter.out.persistence.MembershipRoleRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantEventRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantRepository;
import com.ticket.defender_core.domain.AccessRecordStatus;
import com.ticket.defender_core.domain.AdminMembershipEntity;
import com.ticket.defender_core.domain.EvidenceAsset;
import com.ticket.defender_core.domain.FanReport;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 격리 MySQL 8 스키마에서 관리자 팬 제보 조회의 범위 격리, DATETIME(6) 동일 시각 정렬, 증거 수 집계를 검증합니다.
 * 운영 DB와 MinIO는 사용하지 않으며, 생성한 합성 행은 테스트 종료 시 삭제합니다.
 */
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MYSQL_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:13307/td_issue32.*")
class AdminFanReportReadMySqlTest {
    @Autowired private MockMvc mvc;
    @Autowired private TenantRepository tenants;
    @Autowired private TenantEventRepository events;
    @Autowired private AdminMembershipRepository memberships;
    @Autowired private MembershipRoleRepository roles;
    @Autowired private MembershipEventGrantRepository grants;
    @Autowired private FanReportRepository reports;
    @Autowired private EvidenceAssetRepository assets;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean(name = "adminJwtDecoder")
    private JwtDecoder adminDecoder;

    private final List<UUID> tenantIds = new ArrayList<>();
    private UUID tenantA;
    private UUID tenantB;
    private UUID eventA;
    private UUID eventAOther;
    private UUID eventB;
    private String analyst;
    private String approver;
    private String reviewerB;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TD_ISSUE32_MYSQL_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("TD_ISSUE32_MYSQL_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("TD_ISSUE32_MYSQL_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @BeforeEach
    void seed() {
        // 서명 검증은 H2 API 테스트가 담당하며, 여기서는 검증된 subject만 가진 JWT로 MySQL 조회 범위를 확인합니다.
        given(adminDecoder.decode(anyString())).willAnswer(call -> Jwt.withTokenValue(call.getArgument(0))
                .header("alg", "RS256").subject(call.getArgument(0)).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300)).build());
        String suffix = UUID.randomUUID().toString();
        tenantA = tenant("A");
        tenantB = tenant("B");
        eventA = event(tenantA, "shared-" + suffix, "MySQL 공연 A");
        eventAOther = event(tenantA, "other-" + suffix, "MySQL 다른 공연");
        eventB = event(tenantB, "shared-" + suffix, "MySQL 공연 B");
        analyst = member(tenantA, eventA, "mysql-analyst-" + suffix, AdminRole.ANALYST);
        approver = member(tenantA, eventA, "mysql-approver-" + suffix, AdminRole.APPROVER);
        reviewerB = member(tenantB, eventB, "mysql-reviewer-b-" + suffix, AdminRole.REVIEWER);
    }

    @AfterEach
    void cleanup() {
        for (UUID tenant : tenantIds) {
            jdbc.update("delete a from evidence_assets a join fan_reports r on r.id = a.fan_report_id"
                    + " where r.tenant_id = ?", tenant.toString());
            jdbc.update("delete from fan_reports where tenant_id = ?", tenant.toString());
            jdbc.update("delete r from membership_roles r join memberships m on m.id = r.membership_id"
                    + " where m.tenant_id = ?", tenant.toString());
            jdbc.update("delete from membership_events where tenant_id = ?", tenant.toString());
            jdbc.update("delete from memberships where tenant_id = ?", tenant.toString());
            jdbc.update("delete from events where tenant_id = ?", tenant.toString());
            jdbc.update("delete from tenants where id = ?", tenant.toString());
        }
    }

    @Test
    void listIsScopedStableOnEqualMicrosecondTimestampsAndCountsEvidenceInOneQuery() throws Exception {
        LocalDateTime received = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        List<UUID> sameInstant = new ArrayList<>();
        for (int i = 0; i < 3; i++) sameInstant.add(report(tenantA, eventA, received, true));
        UUID newest = report(tenantA, eventA, received.plusNanos(1_000), false);
        asset(sameInstant.get(0), received, EvidenceAsset.StorageStatus.STORED);
        asset(sameInstant.get(0), received, EvidenceAsset.StorageStatus.FAILED);
        asset(sameInstant.get(0), received, EvidenceAsset.StorageStatus.PENDING);
        report(tenantA, eventAOther, received.plusSeconds(1), true);
        report(tenantB, eventB, received.plusSeconds(1), true);
        List<String> expected = sameInstant.stream().map(UUID::toString).sorted((a, b) -> b.compareTo(a)).toList();
        int withEvidence = expected.indexOf(sameInstant.get(0).toString());

        mvc.perform(get("/api/v1/admin/fan-reports").param("eventId", eventA.toString()).param("pageSize", "2")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + analyst))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.event.name").value("MySQL 공연 A"))
                .andExpect(jsonPath("$.totalItems").value(4))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.items[0].fanReportId").value(newest.toString()))
                .andExpect(jsonPath("$.items[0].intakeStatus").value("PENDING_STORAGE"))
                .andExpect(jsonPath("$.items[1].fanReportId").value(expected.get(0)));
        mvc.perform(get("/api/v1/admin/fan-reports").param("eventId", eventA.toString())
                        .param("page", "1").param("pageSize", "2")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + analyst))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.items[0].fanReportId").value(expected.get(1)))
                .andExpect(jsonPath("$.items[1].fanReportId").value(expected.get(2)));
        mvc.perform(get("/api/v1/admin/fan-reports").param("eventId", eventA.toString())
                        .param("intakeStatus", "RECEIVED")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + analyst))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.items[" + withEvidence + "].evidenceCount").value(3))
                .andExpect(jsonPath("$.items[" + withEvidence + "].storedEvidenceCount").value(1));
        mvc.perform(get("/api/v1/admin/fan-reports").param("eventId", eventB.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + analyst))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/fan-reports").param("eventId", eventAOther.toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + analyst))
                .andExpect(status().isNotFound());
    }

    @Test
    void detailUsesStoredScopeAndReturnsOnlyOwnedEvidenceWithoutStorageSecrets() throws Exception {
        LocalDateTime received = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        UUID reportId = report(tenantA, eventA, received, true);
        UUID stored = asset(reportId, received, EvidenceAsset.StorageStatus.STORED);
        UUID pending = asset(reportId, received.plusSeconds(1), EvidenceAsset.StorageStatus.PENDING);
        UUID foreignReport = report(tenantB, eventB, received, true);
        UUID foreignAsset = asset(foreignReport, received, EvidenceAsset.StorageStatus.STORED);
        UUID otherEventReport = report(tenantA, eventAOther, received, true);

        mvc.perform(get("/api/v1/admin/fan-reports/{id}", reportId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + analyst))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.event.id").value(eventA.toString()))
                .andExpect(jsonPath("$.evidence.length()").value(2))
                .andExpect(jsonPath("$.evidence[0].evidenceId").value(stored.toString()))
                .andExpect(jsonPath("$.evidence[0].originalStored").value(true))
                .andExpect(jsonPath("$.evidence[1].evidenceId").value(pending.toString()))
                .andExpect(jsonPath("$.evidence[1].originalStored").value(false))
                .andExpect(jsonPath("$.evidence[1].sha256").doesNotExist())
                .andExpect(content().string(not(containsString(foreignAsset.toString()))))
                .andExpect(content().string(not(containsString("fan-reports/"))))
                .andExpect(content().string(not(containsString("mysql-object-version"))))
                .andExpect(content().string(not(containsString("FR-MY-"))));
        mvc.perform(get("/api/v1/admin/fan-reports/{id}", foreignReport)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + analyst))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/fan-reports/{id}", otherEventReport)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + analyst))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/fan-reports/{id}", reportId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + approver))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/fan-reports/{id}", reportId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + reviewerB))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/fan-reports/{id}", reportId)).andExpect(status().isUnauthorized());
    }

    private UUID tenant(String label) {
        UUID id = UUID.randomUUID();
        tenants.saveAndFlush(new Tenant(id, "MySQL 합성 고객사 " + label, AccessRecordStatus.ACTIVE));
        tenantIds.add(id);
        return id;
    }

    private UUID event(UUID tenant, String externalRef, String name) {
        UUID id = UUID.randomUUID();
        events.saveAndFlush(new TenantEvent(id, tenant, externalRef, name, AccessRecordStatus.ACTIVE));
        return id;
    }

    private String member(UUID tenant, UUID event, String subject, AdminRole role) {
        UUID membership = UUID.randomUUID();
        memberships.saveAndFlush(new AdminMembershipEntity(membership, tenant, subject, AccessRecordStatus.ACTIVE));
        roles.saveAndFlush(new MembershipRole(UUID.randomUUID(), membership, role));
        grants.saveAndFlush(new MembershipEventGrant(UUID.randomUUID(), membership, tenant, event));
        return subject;
    }

    private UUID report(UUID tenant, UUID event, LocalDateTime receivedAt, boolean received) {
        UUID id = UUID.randomUUID();
        String receipt = "FR-MY-" + id.toString().substring(0, 8) + id.toString().substring(24);
        FanReport report = new FanReport(id, receipt, tenant, event, receivedAt.plusDays(10), "합성 구역",
                FanReport.ReportType.PREMIUM_RESALE, "MySQL 합성 제보", null, null, null,
                new BigDecimal("120000.00"), receivedAt, UUID.randomUUID().toString(), "d".repeat(64),
                "e".repeat(64));
        if (received) report.markReceived();
        reports.saveAndFlush(report);
        return id;
    }

    private UUID asset(UUID reportId, LocalDateTime createdAt, EvidenceAsset.StorageStatus status) {
        UUID id = UUID.randomUUID();
        EvidenceAsset asset = new EvidenceAsset(id, reportId, "fan-reports/" + reportId + "/" + id, "image/png",
                2048, "a".repeat(64), createdAt, createdAt.plusDays(90));
        if (status == EvidenceAsset.StorageStatus.STORED) asset.markStored("mysql-object-version");
        if (status == EvidenceAsset.StorageStatus.FAILED) asset.markFailed();
        assets.saveAndFlush(asset);
        return id;
    }
}
