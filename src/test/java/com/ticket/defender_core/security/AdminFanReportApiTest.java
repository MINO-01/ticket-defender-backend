package com.ticket.defender_core.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.ticket.defender_core.adapter.out.persistence.AdminMembershipRepository;
import com.ticket.defender_core.adapter.out.persistence.EvidenceAssetRepository;
import com.ticket.defender_core.adapter.out.persistence.FanReportRepository;
import com.ticket.defender_core.adapter.out.persistence.MembershipEventGrantRepository;
import com.ticket.defender_core.adapter.out.persistence.MembershipRoleRepository;
import com.ticket.defender_core.adapter.out.persistence.ListingContextAnalysisRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantEventRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantRepository;
import com.ticket.defender_core.domain.AccessRecordStatus;
import com.ticket.defender_core.domain.AdminMembershipEntity;
import com.ticket.defender_core.domain.EvidenceAsset;
import com.ticket.defender_core.domain.FanReport;
import com.ticket.defender_core.domain.MembershipEventGrant;
import com.ticket.defender_core.domain.MembershipRole;
import com.ticket.defender_core.domain.ListingContextAnalysis;
import com.ticket.defender_core.domain.Tenant;
import com.ticket.defender_core.domain.TenantEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 관리자 팬 제보 목록과 상세 조회 API의 인증, tenant/event 범위, 응답 최소화를 합성 데이터로 검증합니다. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:admin-fan-report-api;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"
})
@AutoConfigureMockMvc
@Transactional
class AdminFanReportApiTest {
    private static final String ADMIN_ISSUER = "https://issuer.example.test/admin";
    private static final String ADMIN_AUDIENCE = "ticket-defender-admin";
    private static final UUID TENANT_A = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID EVENT_A = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID EVENT_B = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID EVENT_A_OTHER = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final LocalDateTime RECEIVED = LocalDateTime.parse("2026-10-30T09:00:00");
    private static KeyPair keyPair;

    @Autowired private MockMvc mvc;
    @Autowired private TenantRepository tenants;
    @Autowired private TenantEventRepository events;
    @Autowired private AdminMembershipRepository memberships;
    @Autowired private MembershipRoleRepository roles;
    @Autowired private MembershipEventGrantRepository eventGrants;
    @Autowired private FanReportRepository reports;
    @Autowired private EvidenceAssetRepository assets;
    @Autowired private ListingContextAnalysisRepository analyses;

    @MockitoBean(name = "adminJwtDecoder")
    private JwtDecoder adminDecoder;

    @BeforeAll
    static void createKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
    }

    @BeforeEach
    void seed() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keyPair.getPublic()).build();
        decoder.setJwtValidator(JwtDecoderFactory.validator(ADMIN_ISSUER, ADMIN_AUDIENCE, null));
        given(adminDecoder.decode(anyString())).willAnswer(call -> decoder.decode(call.getArgument(0)));

        tenants.save(new Tenant(TENANT_A, "고객사 A", AccessRecordStatus.ACTIVE));
        tenants.save(new Tenant(TENANT_B, "고객사 B", AccessRecordStatus.ACTIVE));
        events.save(new TenantEvent(EVENT_A, TENANT_A, "shared-event", "공연 A", AccessRecordStatus.ACTIVE));
        events.save(new TenantEvent(EVENT_B, TENANT_B, "shared-event", "공연 B", AccessRecordStatus.ACTIVE));
        events.save(new TenantEvent(EVENT_A_OTHER, TENANT_A, "other-event", "다른 공연", AccessRecordStatus.ACTIVE));
        member(TENANT_A, EVENT_A, "analyst-a", AdminRole.ANALYST);
        member(TENANT_A, EVENT_A, "reviewer-a", AdminRole.REVIEWER);
        member(TENANT_A, EVENT_A, "approver-a", AdminRole.APPROVER);
        member(TENANT_A, EVENT_A, "admin-only-a", AdminRole.TENANT_ADMIN);
        member(TENANT_A, EVENT_A, "no-role-a", null);
        member(TENANT_B, EVENT_B, "reviewer-b", AdminRole.REVIEWER);
    }

    @Test
    void authorizedEventListsOnlyItsStoredReportsWithEvidenceCounts() throws Exception {
        UUID first = report(TENANT_A, EVENT_A, "aaaaaaaa-0000-4000-8000-000000000001", RECEIVED, true);
        stored(first, "10000000-0000-4000-8000-000000000001");
        pending(first, "10000000-0000-4000-8000-000000000002");
        UUID second = report(TENANT_A, EVENT_A, "aaaaaaaa-0000-4000-8000-000000000002",
                RECEIVED.plusMinutes(5), false);
        report(TENANT_A, EVENT_A_OTHER, "aaaaaaaa-0000-4000-8000-000000000003", RECEIVED.plusMinutes(9), true);
        report(TENANT_B, EVENT_B, "aaaaaaaa-0000-4000-8000-000000000004", RECEIVED.plusMinutes(9), true);

        mvc.perform(list(EVENT_A).header(HttpHeaders.AUTHORIZATION, bearer("analyst-a")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.event.id").value(EVENT_A.toString()))
                .andExpect(jsonPath("$.event.externalEventId").value("shared-event"))
                .andExpect(jsonPath("$.event.name").value("공연 A"))
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].fanReportId").value(second.toString()))
                .andExpect(jsonPath("$.items[0].receiptNumber").value("FR-SYNTH-aaaaaaaa002"))
                .andExpect(jsonPath("$.items[0].intakeStatus").value("PENDING_STORAGE"))
                .andExpect(jsonPath("$.items[0].evidenceCount").value(0))
                .andExpect(jsonPath("$.items[0].storedEvidenceCount").value(0))
                .andExpect(jsonPath("$.items[1].fanReportId").value(first.toString()))
                .andExpect(jsonPath("$.items[1].receiptNumber").value("FR-SYNTH-aaaaaaaa001"))
                .andExpect(jsonPath("$.items[1].reportType").value("PREMIUM_RESALE"))
                .andExpect(jsonPath("$.items[1].intakeStatus").value("RECEIVED"))
                .andExpect(jsonPath("$.items[1].receivedAt").value("2026-10-30T09:00:00Z"))
                .andExpect(jsonPath("$.items[1].evidenceCount").value(2))
                .andExpect(jsonPath("$.items[1].storedEvidenceCount").value(1))
                .andExpect(jsonPath("$.items[1].description").doesNotExist());

        mvc.perform(list(EVENT_A).param("intakeStatus", "RECEIVED")
                        .header(HttpHeaders.AUTHORIZATION, bearer("reviewer-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].fanReportId").value(first.toString()));
    }

    @Test
    void authorizedEventWithoutReportsReturnsAnEmptyPage() throws Exception {
        mvc.perform(list(EVENT_A).header(HttpHeaders.AUTHORIZATION, bearer("analyst-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalItems").value(0))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.pageSize").value(20))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void detailReturnsReportContentAndOnlyItsOwnEvidenceMetadata() throws Exception {
        UUID reportId = report(TENANT_A, EVENT_A, "bbbbbbbb-0000-4000-8000-000000000001", RECEIVED, true);
        UUID storedId = stored(reportId, "20000000-0000-4000-8000-000000000001");
        UUID failedId = failed(reportId, "20000000-0000-4000-8000-000000000002");
        UUID otherReport = report(TENANT_A, EVENT_A, "bbbbbbbb-0000-4000-8000-000000000002", RECEIVED, true);
        stored(otherReport, "20000000-0000-4000-8000-000000000003");

        mvc.perform(detail(reportId).header(HttpHeaders.AUTHORIZATION, bearer("reviewer-a")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.fanReportId").value(reportId.toString()))
                .andExpect(jsonPath("$.receiptNumber").isString())
                .andExpect(jsonPath("$.event.id").value(EVENT_A.toString()))
                .andExpect(jsonPath("$.event.name").value("공연 A"))
                .andExpect(jsonPath("$.reportType").value("PREMIUM_RESALE"))
                .andExpect(jsonPath("$.intakeStatus").value("RECEIVED"))
                .andExpect(jsonPath("$.receivedAt").value("2026-10-30T09:00:00Z"))
                .andExpect(jsonPath("$.retentionUntil").value("2027-01-28T09:00:00Z"))
                .andExpect(jsonPath("$.submittedPerformanceAt").value("2026-11-20T19:00:00Z"))
                .andExpect(jsonPath("$.seatArea").value("A구역"))
                .andExpect(jsonPath("$.description").value("합성 제보 설명"))
                .andExpect(jsonPath("$.listingUrl").value("https://resale.example.test/listing/1"))
                .andExpect(jsonPath("$.platform").value("합성 플랫폼"))
                .andExpect(jsonPath("$.seller").value("synthetic-seller"))
                .andExpect(jsonPath("$.askingPrice").value(250000.0))
                .andExpect(jsonPath("$.evidence.length()").value(2))
                .andExpect(jsonPath("$.evidence[0].evidenceId").value(storedId.toString()))
                .andExpect(jsonPath("$.evidence[0].storageStatus").value("STORED"))
                .andExpect(jsonPath("$.evidence[0].originalStored").value(true))
                .andExpect(jsonPath("$.evidence[0].sha256").value("c".repeat(64)))
                .andExpect(jsonPath("$.evidence[0].mediaType").value("image/png"))
                .andExpect(jsonPath("$.evidence[0].byteSize").value(1024))
                .andExpect(jsonPath("$.evidence[1].evidenceId").value(failedId.toString()))
                .andExpect(jsonPath("$.evidence[1].storageStatus").value("FAILED"))
                .andExpect(jsonPath("$.evidence[1].originalStored").value(false))
                .andExpect(jsonPath("$.evidence[1].sha256").doesNotExist())
                .andExpect(content().string(not(containsString("20000000-0000-4000-8000-000000000003"))));

        // 상세 응답의 evidenceId로 기존 Listing Context 분석 이력 API를 같은 권한으로 호출할 수 있어야 합니다.
        mvc.perform(get("/api/v1/admin/fan-reports/{fanReportId}/evidence/{evidenceId}/listing-analyses",
                        reportId, storedId).header(HttpHeaders.AUTHORIZATION, bearer("analyst-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void responsesExposeOnlyTheReceiptNumberAndNeverRecoverySecretsOrDigests() throws Exception {
        UUID reportId = report(TENANT_A, EVENT_A, "cccccccc-0000-4000-8000-000000000001", RECEIVED, true);
        stored(reportId, "30000000-0000-4000-8000-000000000001");
        for (MockHttpServletRequestBuilder request : List.of(list(EVENT_A), detail(reportId))) {
            mvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer("analyst-a")))
                    .andExpect(status().isOk())
                    .andExpect(content().string(not(containsString("fan-reports/"))))
                    .andExpect(content().string(not(containsString("object-version-secret"))))
                    .andExpect(jsonPath("$..receiptNumber").isNotEmpty())
                    .andExpect(content().string(not(containsString("idem-digest-"))))
                    .andExpect(content().string(not(containsString("d".repeat(64)))))
                    .andExpect(content().string(not(containsString("e".repeat(64)))))
                    .andExpect(content().string(not(containsString("objectKey"))))
                    .andExpect(content().string(not(containsString("objectVersion"))))
                    .andExpect(content().string(not(containsString("lookupSecretDigest"))))
                    .andExpect(content().string(not(containsString("tenantId"))))
                    .andExpect(content().string(not(containsString("Digest"))));
        }
    }

    @Test
    void analystReadsPersistedTrack2RunsAndUnrelatedRoleIsDenied() throws Exception {
        UUID reportId = report(TENANT_A, EVENT_A, "cccccccc-0000-4000-8000-000000000009", RECEIVED, true);
        UUID evidenceId = stored(reportId, "30000000-0000-4000-8000-000000000009");
        analyses.saveAndFlush(new ListingContextAnalysis(UUID.randomUUID(), TENANT_A, EVENT_A, reportId, evidenceId,
                memberships.findBySubjectAndTenantId("analyst-a", TENANT_A).orElseThrow().getId(), "d".repeat(64),
                UUID.randomUUID(), "a".repeat(64), "marker", "b".repeat(64), 1024, "image/png", RECEIVED,
                RECEIVED.plusMinutes(5)));

        mvc.perform(get("/api/v1/admin/analysis-runs").param("eventId", EVENT_A.toString())
                        .header(HttpHeaders.AUTHORIZATION, bearer("analyst-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coverage").value("PERSISTED_TRACK2_LISTING_CONTEXT_ANALYSES_ONLY"))
                .andExpect(jsonPath("$.items[0].fanReportId").value(reportId.toString()))
                .andExpect(jsonPath("$.items[0].executionStatus").value("IN_PROGRESS"));
        mvc.perform(get("/api/v1/admin/analysis-runs").param("eventId", EVENT_A.toString())
                        .header(HttpHeaders.AUTHORIZATION, bearer("approver-a")))
                .andExpect(status().isForbidden());
    }

    @Test
    void analysisRunPagingValidationUsesExistingInvalidRequestContract() throws Exception {
        for (MockHttpServletRequestBuilder request : List.of(
                get("/api/v1/admin/analysis-runs").param("eventId", EVENT_A.toString()).param("pageSize", "0"),
                get("/api/v1/admin/analysis-runs").param("eventId", EVENT_A.toString()).param("page", "-1"))) {
            mvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer("analyst-a")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }

    @Test
    void tenantAdminReadsSafeOperationStateAndAnalystIsDenied() throws Exception {
        mvc.perform(get("/api/v1/admin/operations/settings").param("eventId", EVENT_A.toString())
                        .header(HttpHeaders.AUTHORIZATION, bearer("admin-only-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.track1RelationshipEvidenceCoverage").value("STORED_DEVICE_AND_IP_SIGNALS_ONLY"))
                .andExpect(jsonPath("$.auditorAccessState").value("NOT_IMPLEMENTED_POLICY_PENDING"))
                .andExpect(content().string(not(containsString("secret"))))
                .andExpect(content().string(not(containsString("endpoint"))));
        mvc.perform(get("/api/v1/admin/operations/settings").param("eventId", EVENT_A.toString())
                        .header(HttpHeaders.AUTHORIZATION, bearer("analyst-a")))
                .andExpect(status().isForbidden());
    }

    @Test
    void otherTenantOtherEventAndUnknownResourcesAreHiddenAs404() throws Exception {
        UUID otherTenantReport = report(TENANT_B, EVENT_B, "dddddddd-0000-4000-8000-000000000001", RECEIVED, true);
        UUID otherEventReport = report(TENANT_A, EVENT_A_OTHER, "dddddddd-0000-4000-8000-000000000002",
                RECEIVED, true);

        for (MockHttpServletRequestBuilder request : List.of(list(EVENT_B), list(EVENT_A_OTHER),
                list(UUID.randomUUID()), detail(otherTenantReport), detail(otherEventReport),
                detail(UUID.randomUUID()))) {
            mvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer("analyst-a")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                    .andExpect(content().string(not(containsString("공연 B"))));
        }
        mvc.perform(detail(otherTenantReport).header(HttpHeaders.AUTHORIZATION, bearer("reviewer-b")))
                .andExpect(status().isOk());
    }

    @Test
    void missingOrInvalidTokensAreRejectedAs401() throws Exception {
        UUID reportId = report(TENANT_A, EVENT_A, "eeeeeeee-0000-4000-8000-000000000001", RECEIVED, true);
        for (MockHttpServletRequestBuilder request : List.of(list(EVENT_A), detail(reportId))) {
            mvc.perform(request).andExpect(status().isUnauthorized());
        }
        mvc.perform(list(EVENT_A).header(HttpHeaders.AUTHORIZATION, "Bearer broken"))
                .andExpect(status().isUnauthorized());
        mvc.perform(detail(reportId).header(HttpHeaders.AUTHORIZATION,
                        token("analyst-a", "https://issuer.example.test/service", "ticket-defender-analysis")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void sameTenantMembersWithoutAnalystOrReviewerRoleGet403() throws Exception {
        UUID reportId = report(TENANT_A, EVENT_A, "ffffffff-0000-4000-8000-000000000001", RECEIVED, true);
        for (String subject : List.of("approver-a", "admin-only-a", "no-role-a")) {
            mvc.perform(list(EVENT_A).header(HttpHeaders.AUTHORIZATION, bearer(subject)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            mvc.perform(detail(reportId).header(HttpHeaders.AUTHORIZATION, bearer(subject)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        // 고객사 멤버십이 없는 사용자는 같은 자원이라도 존재 여부를 알 수 없습니다.
        mvc.perform(detail(reportId).header(HttpHeaders.AUTHORIZATION, bearer("reviewer-b")))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidPagingFiltersAndIdentifiersAreRejectedAs400() throws Exception {
        for (MockHttpServletRequestBuilder request : List.of(
                list(EVENT_A).param("page", "-1"),
                list(EVENT_A).param("pageSize", "0"),
                list(EVENT_A).param("pageSize", "101"),
                list(EVENT_A).param("page", "abc"),
                list(EVENT_A).param("page", String.valueOf(Integer.MAX_VALUE)).param("pageSize", "100"),
                list(EVENT_A).param("intakeStatus", "FRAUD_CONFIRMED"),
                get("/api/v1/admin/fan-reports"),
                get("/api/v1/admin/fan-reports").param("eventId", "shared-event"),
                get("/api/v1/admin/fan-reports/not-a-uuid"))) {
            mvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer("analyst-a")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }

    @Test
    void reportsReceivedAtTheSameInstantPageInAStableOrder() throws Exception {
        UUID low = report(TENANT_A, EVENT_A, "99999999-0000-4000-8000-000000000001", RECEIVED, true);
        UUID mid = report(TENANT_A, EVENT_A, "99999999-0000-4000-8000-000000000002", RECEIVED, true);
        UUID high = report(TENANT_A, EVENT_A, "99999999-0000-4000-8000-000000000003", RECEIVED, true);

        mvc.perform(list(EVENT_A).param("pageSize", "2").header(HttpHeaders.AUTHORIZATION, bearer("analyst-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.items[0].fanReportId").value(high.toString()))
                .andExpect(jsonPath("$.items[1].fanReportId").value(mid.toString()));
        mvc.perform(list(EVENT_A).param("page", "1").param("pageSize", "2")
                        .header(HttpHeaders.AUTHORIZATION, bearer("analyst-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].fanReportId").value(low.toString()));
    }

    @Test
    void multiTenantAdminIsBlockedOnFanReportListAndDetailWithoutLeakingReports() throws Exception {
        UUID reportId = report(TENANT_A, EVENT_A, UUID.randomUUID().toString(), RECEIVED, true);
        // 같은 subject가 두 번째 활성 고객사에도 활성 멤버십을 가지게 됩니다.
        UUID extraTenant = UUID.randomUUID();
        tenants.save(new Tenant(extraTenant, "추가 합성 고객사", AccessRecordStatus.ACTIVE));
        memberships.save(new AdminMembershipEntity(UUID.randomUUID(), extraTenant, "analyst-a",
                AccessRecordStatus.ACTIVE));

        for (var request : List.of(list(EVENT_A), detail(reportId))) {
            mvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer("analyst-a")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TENANT_SELECTION_REQUIRED"))
                    .andExpect(jsonPath("$.items").doesNotExist())
                    .andExpect(jsonPath("$.evidence").doesNotExist())
                    .andExpect(content().string(not(containsString(EVENT_A.toString()))))
                    .andExpect(content().string(not(containsString(TENANT_A.toString()))));
        }
        // 다른 고객사 공연과 없는 제보는 기존처럼 404로 숨깁니다.
        mvc.perform(list(EVENT_B).header(HttpHeaders.AUTHORIZATION, bearer("analyst-a")))
                .andExpect(status().isNotFound());
        mvc.perform(detail(UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, bearer("analyst-a")))
                .andExpect(status().isNotFound());
        // 단일 고객사 관리자는 기존처럼 조회합니다.
        mvc.perform(list(EVENT_A).header(HttpHeaders.AUTHORIZATION, bearer("reviewer-a")))
                .andExpect(status().isOk());
    }

    @Test
    void multiTenantAdminIsBlockedOnNewAdminQueriesWithoutLeakingScope() throws Exception {
        UUID extraTenant = UUID.randomUUID();
        tenants.save(new Tenant(extraTenant, "추가 합성 고객사", AccessRecordStatus.ACTIVE));
        memberships.save(new AdminMembershipEntity(UUID.randomUUID(), extraTenant, "analyst-a",
                AccessRecordStatus.ACTIVE));
        memberships.save(new AdminMembershipEntity(UUID.randomUUID(), extraTenant, "admin-only-a",
                AccessRecordStatus.ACTIVE));

        mvc.perform(get("/api/v1/admin/analysis-runs").param("eventId", EVENT_A.toString())
                        .header(HttpHeaders.AUTHORIZATION, bearer("analyst-a")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TENANT_SELECTION_REQUIRED"))
                .andExpect(content().string(not(containsString(EVENT_A.toString()))))
                .andExpect(content().string(not(containsString(TENANT_A.toString()))));
        mvc.perform(get("/api/v1/admin/operations/settings").param("eventId", EVENT_A.toString())
                        .header(HttpHeaders.AUTHORIZATION, bearer("admin-only-a")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TENANT_SELECTION_REQUIRED"))
                .andExpect(content().string(not(containsString(EVENT_A.toString()))))
                .andExpect(content().string(not(containsString(TENANT_A.toString()))));
    }

    private void member(UUID tenant, UUID event, String subject, AdminRole role) {
        UUID membership = UUID.randomUUID();
        memberships.save(new AdminMembershipEntity(membership, tenant, subject, AccessRecordStatus.ACTIVE));
        if (role != null) roles.save(new MembershipRole(UUID.randomUUID(), membership, role));
        eventGrants.save(new MembershipEventGrant(UUID.randomUUID(), membership, tenant, event));
    }

    private UUID report(UUID tenant, UUID event, String id, LocalDateTime receivedAt, boolean received) {
        UUID reportId = UUID.fromString(id);
        FanReport report = new FanReport(reportId, "FR-SYNTH-" + id.substring(0, 8) + id.substring(33), tenant,
                event, LocalDateTime.parse("2026-11-20T19:00:00"), "A구역", FanReport.ReportType.PREMIUM_RESALE,
                "합성 제보 설명", "https://resale.example.test/listing/1", "합성 플랫폼", "synthetic-seller",
                new BigDecimal("250000.00"), receivedAt, "idem-digest-" + id, "d".repeat(64), "e".repeat(64));
        if (received) report.markReceived();
        reports.saveAndFlush(report);
        return reportId;
    }

    private UUID stored(UUID reportId, String id) {
        EvidenceAsset asset = asset(reportId, id);
        asset.markStored("object-version-secret");
        assets.saveAndFlush(asset);
        return asset.getEvidenceId();
    }

    private UUID pending(UUID reportId, String id) {
        EvidenceAsset asset = asset(reportId, id);
        assets.saveAndFlush(asset);
        return asset.getEvidenceId();
    }

    private UUID failed(UUID reportId, String id) {
        EvidenceAsset asset = asset(reportId, id);
        asset.markFailed();
        assets.saveAndFlush(asset);
        return asset.getEvidenceId();
    }

    private static EvidenceAsset asset(UUID reportId, String id) {
        UUID evidenceId = UUID.fromString(id);
        return new EvidenceAsset(evidenceId, reportId, "fan-reports/" + reportId + "/" + evidenceId, "image/png",
                1024, "c".repeat(64), RECEIVED.plusSeconds(Long.parseLong(id.substring(35))),
                RECEIVED.plusDays(90));
    }

    private static MockHttpServletRequestBuilder list(UUID eventId) {
        return get("/api/v1/admin/fan-reports").param("eventId", eventId.toString());
    }

    private static MockHttpServletRequestBuilder detail(UUID reportId) {
        return get("/api/v1/admin/fan-reports/{fanReportId}", reportId);
    }

    private static String bearer(String subject) throws Exception {
        return token(subject, ADMIN_ISSUER, ADMIN_AUDIENCE);
    }

    private static String token(String subject, String issuer, String audience) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(issuer)
                .audience(audience)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner(keyPair.getPrivate()));
        return "Bearer " + jwt.serialize();
    }
}
