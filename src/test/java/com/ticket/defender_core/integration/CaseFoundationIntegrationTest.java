package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.in.web.dto.CaseDto;
import com.ticket.defender_core.adapter.out.persistence.*;
import com.ticket.defender_core.application.service.CaseApiException;
import com.ticket.defender_core.application.service.CaseService;
import com.ticket.defender_core.application.service.EvidenceObjectStorage;
import com.ticket.defender_core.domain.*;
import com.ticket.defender_core.security.AdminRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.model.S3Exception;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:case-foundation;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false"
})
@AutoConfigureMockMvc
@Transactional
class CaseFoundationIntegrationTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID EVENT = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID OTHER_EVENT = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID ANALYST = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID REVIEWER = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID SECOND_ANALYST = UUID.fromString("77777777-7777-4777-8777-777777777777");
    @Autowired private CaseService service;
    @Autowired private CaseRepository cases;
    @Autowired private CaseSourceReferenceRepository sources;
    @Autowired private CaseActivityRepository activities;
    @Autowired private TenantRepository tenants;
    @Autowired private TenantEventRepository events;
    @Autowired private AdminMembershipRepository memberships;
    @Autowired private MembershipRoleRepository roles;
    @Autowired private MembershipEventGrantRepository grants;
    @Autowired private FanReportRepository reports;
    @Autowired private EvidenceAssetRepository assets;
    @Autowired private TicketAuditRepository audits;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @MockitoBean(name = "adminJwtDecoder") private JwtDecoder decoder;
    @MockitoBean private EvidenceObjectStorage storage;

    @BeforeEach
    void seed() {
        given(decoder.decode(anyString())).willAnswer(call -> jwt(call.getArgument(0)));
        tenants.save(new Tenant(TENANT, "Synthetic tenant", AccessRecordStatus.ACTIVE));
        tenants.save(new Tenant(OTHER_TENANT, "Other synthetic tenant", AccessRecordStatus.ACTIVE));
        events.save(new TenantEvent(EVENT, TENANT, "synthetic-show", "Synthetic show", AccessRecordStatus.ACTIVE));
        events.save(new TenantEvent(OTHER_EVENT, OTHER_TENANT, "synthetic-show", "Other show", AccessRecordStatus.ACTIVE));
        member(ANALYST, "analyst", AdminRole.ANALYST);
        member(REVIEWER, "reviewer", AdminRole.REVIEWER);
        member(SECOND_ANALYST, "second", AdminRole.ANALYST);
        UUID approver = UUID.randomUUID();
        member(approver, "approver", AdminRole.APPROVER);
        member(UUID.randomUUID(), "tenant-admin", AdminRole.TENANT_ADMIN);
        as("analyst");
    }

    private void member(UUID id, String subject, AdminRole role) {
        memberships.save(new AdminMembershipEntity(id, TENANT, subject, AccessRecordStatus.ACTIVE));
        roles.save(new MembershipRole(UUID.randomUUID(), id, role));
        grants.save(new MembershipEventGrant(UUID.randomUUID(), id, TENANT, EVENT));
    }

    private static Jwt jwt(String subject) {
        return Jwt.withTokenValue(subject).header("alg", "RS256").subject(subject).build();
    }

    private void as(String subject) {
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt(subject));
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private FanReport report(UUID tenantId, UUID eventId) {
        UUID id = UUID.randomUUID();
        FanReport report = new FanReport(id, "FR-" + id, tenantId, eventId,
                LocalDateTime.now().plusDays(1), null, FanReport.ReportType.OTHER,
                "Synthetic report", null, null, null, null, LocalDateTime.now(),
                UUID.randomUUID().toString(), "d".repeat(64), "e".repeat(64));
        report.markReceived();
        return reports.saveAndFlush(report);
    }

    private CaseDto.Create createInput(UUID reportId) {
        return new CaseDto.Create(EVENT, Case.Type.FAN_REPORT, List.of(), List.of(reportId), List.of(),
                "Investigate this synthetic report", null, null);
    }

    @Test
    void httpCreateAndReadEnforceMembershipAndEventBoundaries() throws Exception {
        UUID reportId = report(TENANT, EVENT).getFanReportId();
        mvc.perform(post("/api/v1/cases").header("Authorization", "Bearer analyst")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(createInput(reportId))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.evidenceRevision").value(1));
        UUID id = cases.findAll().getFirst().getId();
        mvc.perform(get("/api/v1/cases").param("eventId", EVENT.toString())
                .header("Authorization", "Bearer analyst"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/v1/cases").param("eventId", EVENT.toString())
                .header("Authorization", "Bearer reviewer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/v1/cases").param("eventId", EVENT.toString())
                .header("Authorization", "Bearer tenant-admin"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/v1/cases/" + id).header("Authorization", "Bearer second"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/cases").param("eventId", EVENT.toString())
                .header("Authorization", "Bearer second"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/v1/cases/" + id).header("Authorization", "Bearer approver"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/cases").param("eventId", OTHER_EVENT.toString())
                .header("Authorization", "Bearer analyst"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/cases/" + id)).andExpect(status().isUnauthorized());
    }

    @Test
    void replayDoesNotDuplicateCaseAndChangedPayloadConflicts() {
        as("analyst");
        UUID reportId = report(TENANT, EVENT).getFanReportId();
        String key = UUID.randomUUID().toString();
        CaseDto.View first = service.create(createInput(reportId), key);
        assertThat(service.create(createInput(reportId), key).caseId()).isEqualTo(first.caseId());
        assertThat(cases.count()).isEqualTo(1);
        assertThatThrownBy(() -> service.create(new CaseDto.Create(EVENT, Case.Type.FAN_REPORT,
                List.of(), List.of(reportId), List.of(), "A changed reason", null, null), key))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        assertThatThrownBy(() -> service.create(new CaseDto.Create(EVENT, Case.Type.FAN_REPORT,
                List.of(), java.util.Arrays.asList(reportId, null), List.of(),
                "Investigate this synthetic report", null, null), key))
                .isInstanceOf(CaseApiException.class);
    }

    @Test
    void rejectsCrossTenantAndCrossEventSources() {
        as("analyst");
        FanReport wrongTenant = report(OTHER_TENANT, OTHER_EVENT);
        assertThatThrownBy(() -> service.create(createInput(wrongTenant.getFanReportId()), UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("RESOURCE_NOT_FOUND"));
        UUID sameTenantOtherEvent = UUID.randomUUID();
        events.save(new TenantEvent(sameTenantOtherEvent, TENANT, "other-show", "Other", AccessRecordStatus.ACTIVE));
        FanReport wrongEvent = report(TENANT, sameTenantOtherEvent);
        assertThatThrownBy(() -> service.create(createInput(wrongEvent.getFanReportId()), UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("RESOURCE_NOT_FOUND"));
    }

    @Test
    void assetRequiresOwnershipAndPrivateHeadMetadata() {
        as("analyst");
        FanReport owner = report(TENANT, EVENT);
        FanReport other = report(TENANT, EVENT);
        UUID assetId = UUID.randomUUID();
        String key = "fan-reports/synthetic/" + assetId;
        EvidenceAsset asset = new EvidenceAsset(assetId, owner.getFanReportId(), key,
                "image/png", 4, "a".repeat(64), LocalDateTime.now(), LocalDateTime.now().plusDays(90));
        asset.markStored("opaque-marker");
        assets.saveAndFlush(asset);
        CaseDto.Create wrongOwner = new CaseDto.Create(EVENT, Case.Type.FAN_REPORT, List.of(),
                List.of(other.getFanReportId()), List.of(assetId), "Synthetic owner check", null, null);
        assertThatThrownBy(() -> service.create(wrongOwner, UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("RESOURCE_NOT_FOUND"));
        CaseDto.Create valid = new CaseDto.Create(EVENT, Case.Type.FAN_REPORT, List.of(),
                List.of(owner.getFanReportId()), List.of(assetId), "Synthetic owner check", null, null);
        given(storage.head(key)).willReturn(new EvidenceObjectStorage.ObjectMetadata(5, "image/png", "opaque-marker"));
        assertThatThrownBy(() -> service.create(valid, UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("SOURCE_OBJECT_MISMATCH"));
        given(storage.head(key)).willReturn(new EvidenceObjectStorage.ObjectMetadata(4, "image/png", "opaque-marker"));
        assertThat(service.create(valid, UUID.randomUUID().toString()).status()).isEqualTo(Case.Status.OPEN);
        org.mockito.Mockito.verify(storage, org.mockito.Mockito.times(2)).head(key);
    }

    @Test
    void missingObjectAndStorageOutageAreDifferentFromMissingDatabaseSource() {
        as("analyst");
        FanReport owner = report(TENANT, EVENT);
        UUID assetId = UUID.randomUUID();
        String key = "fan-reports/synthetic/" + assetId;
        EvidenceAsset asset = new EvidenceAsset(assetId, owner.getFanReportId(), key,
                "image/png", 4, "a".repeat(64), LocalDateTime.now(), LocalDateTime.now().plusDays(90));
        asset.markStored("opaque-marker");
        assets.saveAndFlush(asset);
        CaseDto.Create input = new CaseDto.Create(EVENT, Case.Type.FAN_REPORT, List.of(),
                List.of(owner.getFanReportId()), List.of(assetId), "Verify private image", null, null);
        org.mockito.BDDMockito.willThrow(S3Exception.builder().statusCode(404).build())
                .given(storage).head(key);
        assertThatThrownBy(() -> service.create(input, UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("SOURCE_OBJECT_MISSING"));
        org.mockito.BDDMockito.willThrow(S3Exception.builder().statusCode(503).build())
                .given(storage).checkReady();
        assertThatThrownBy(() -> service.create(input, UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("DEPENDENCY_UNAVAILABLE"));
        org.mockito.BDDMockito.willThrow(S3Exception.builder().statusCode(503).build())
                .given(storage).head(key);
        assertThatThrownBy(() -> service.create(input, UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("DEPENDENCY_UNAVAILABLE"));
        assertThat(cases.count()).isZero();
    }

    @Test
    void graphClusterMustExistInTheStoredTenantEventScope() {
        as("analyst");
        String requestId = UUID.randomUUID().toString();
        audits.saveAndFlush(TicketAudit.createMacroAudit(new MacroAnalysisEvidence(
                "synthetic-account", "synthetic-reservation", "synthetic-show", "a".repeat(64),
                null, "b".repeat(64), null, "cluster-a", 0.8, "LOUVAIN", "1.0.0", requestId,
                LocalDateTime.now(), TENANT, EVENT)));
        CaseDto.Create valid = new CaseDto.Create(EVENT, Case.Type.GRAPH_CLUSTER,
                List.of(new CaseDto.GraphCluster(requestId, "cluster-a")), List.of(), List.of(),
                "Investigate persisted graph evidence", null, null);
        CaseDto.View created = service.create(valid, UUID.randomUUID().toString());
        assertThat(service.sources(created.caseId())).singleElement().satisfies(source -> {
            assertThat(source.sourceType()).isEqualTo(CaseSourceReference.Type.STORED_CLUSTER_EVIDENCE);
            assertThat(source.storedMemberCount()).isEqualTo(1);
        });
        CaseDto.Create missing = new CaseDto.Create(EVENT, Case.Type.GRAPH_CLUSTER,
                List.of(new CaseDto.GraphCluster(requestId, "other-cluster")), List.of(), List.of(),
                "Missing stored cluster", null, null);
        assertThatThrownBy(() -> service.create(missing, UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("RESOURCE_NOT_FOUND"));
    }

    @Test
    void expiredReportAndPendingEvidenceCannotBecomeCaseSources() {
        as("analyst");
        UUID expiredId = UUID.randomUUID();
        FanReport expired = new FanReport(expiredId, "FR-" + expiredId, TENANT, EVENT,
                LocalDateTime.now().minusDays(100), null, FanReport.ReportType.OTHER,
                "Synthetic expired report", null, null, null, null, LocalDateTime.now().minusDays(100),
                UUID.randomUUID().toString(), "d".repeat(64), "e".repeat(64));
        expired.markReceived();
        reports.saveAndFlush(expired);
        assertThatThrownBy(() -> service.create(createInput(expiredId), UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("SOURCE_NOT_AVAILABLE"));

        FanReport received = report(TENANT, EVENT);
        UUID pendingId = UUID.randomUUID();
        assets.saveAndFlush(new EvidenceAsset(pendingId, received.getFanReportId(),
                "fan-reports/synthetic/" + pendingId, "image/png", 4,
                "a".repeat(64), LocalDateTime.now(), LocalDateTime.now().plusDays(90)));
        CaseDto.Create input = new CaseDto.Create(EVENT, Case.Type.FAN_REPORT, List.of(),
                List.of(received.getFanReportId()), List.of(pendingId), "Pending file source", null, null);
        assertThatThrownBy(() -> service.create(input, UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("SOURCE_NOT_AVAILABLE"));
    }

    @Test
    void assigneeMustHaveCurrentRoleAndEventGrant() {
        as("analyst");
        UUID reportId = report(TENANT, EVENT).getFanReportId();
        CaseDto.View created = service.create(createInput(reportId), UUID.randomUUID().toString());
        as("reviewer");
        UUID noGrant = UUID.randomUUID();
        memberships.save(new AdminMembershipEntity(noGrant, TENANT, "no-grant", AccessRecordStatus.ACTIVE));
        roles.save(new MembershipRole(UUID.randomUUID(), noGrant, AdminRole.ANALYST));
        assertThatThrownBy(() -> service.assign(created.caseId(),
                new CaseDto.Assign(noGrant, created.caseVersion(), "Attempt missing grant"),
                UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("RESOURCE_NOT_FOUND"));
        UUID approver = memberships.findBySubjectAndTenantId("approver", TENANT).orElseThrow().getId();
        assertThatThrownBy(() -> service.assign(created.caseId(),
                new CaseDto.Assign(approver, created.caseVersion(), "Attempt wrong role"),
                UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("ASSIGNEE_ROLE_INVALID"));
    }

    @Test
    void duplicateSourceRequiresExplicitFollowUpToTerminalCase() {
        as("analyst");
        UUID reportId = report(TENANT, EVENT).getFanReportId();
        CaseDto.View first = service.create(createInput(reportId), UUID.randomUUID().toString());
        assertThatThrownBy(() -> service.create(createInput(reportId), UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("SOURCE_ALREADY_LINKED"));
        Case prior = cases.findById(first.caseId()).orElseThrow();
        prior.transition(Case.Status.DISMISSED, null, LocalDateTime.now());
        cases.saveAndFlush(prior);
        CaseDto.Create followUp = new CaseDto.Create(EVENT, Case.Type.FAN_REPORT, List.of(),
                List.of(reportId), List.of(), "New reason to investigate", first.caseId(),
                "New synthetic information supplied");
        CaseDto.View reopened = service.create(followUp, UUID.randomUUID().toString());
        assertThat(reopened.relatedCaseId()).isEqualTo(first.caseId());
        assertThat(cases.count()).isEqualTo(2);
    }

    @Test
    void terminalFollowUpChainCanReuseSourceWithoutChangingPriorCases() {
        UUID reportId = report(TENANT, EVENT).getFanReportId();
        CaseDto.View first = service.create(createInput(reportId), UUID.randomUUID().toString());
        CaseDto.View dismissedFirst = dismissThroughReviewer(first);
        CaseDto.Create secondInput = followUpInput(reportId, first.caseId());
        CaseDto.View second = service.create(secondInput, UUID.randomUUID().toString());
        CaseDto.View dismissedSecond = dismissThroughReviewer(second);
        long firstActivities = activities.findByCaseId(first.caseId(),
                org.springframework.data.domain.Pageable.unpaged()).getTotalElements();
        long secondActivities = activities.findByCaseId(second.caseId(),
                org.springframework.data.domain.Pageable.unpaged()).getTotalElements();

        CaseDto.Create thirdInput = followUpInput(reportId, second.caseId());
        String thirdKey = UUID.randomUUID().toString();
        CaseDto.View third = service.create(thirdInput, thirdKey);

        assertThat(List.of(first.caseId(), second.caseId(), third.caseId())).doesNotHaveDuplicates();
        assertThat(second.relatedCaseId()).isEqualTo(first.caseId());
        assertThat(third.relatedCaseId()).isEqualTo(second.caseId());
        assertThat(cases.findById(first.caseId()).orElseThrow().getStatus()).isEqualTo(Case.Status.DISMISSED);
        assertThat(cases.findById(second.caseId()).orElseThrow().getStatus()).isEqualTo(Case.Status.DISMISSED);
        assertThat(cases.findById(first.caseId()).orElseThrow().getCaseVersion())
                .isEqualTo(dismissedFirst.caseVersion());
        assertThat(cases.findById(second.caseId()).orElseThrow().getCaseVersion())
                .isEqualTo(dismissedSecond.caseVersion());
        assertThat(activities.findByCaseId(first.caseId(),
                org.springframework.data.domain.Pageable.unpaged()).getTotalElements()).isEqualTo(firstActivities);
        assertThat(activities.findByCaseId(second.caseId(),
                org.springframework.data.domain.Pageable.unpaged()).getTotalElements()).isEqualTo(secondActivities);
        assertThat(service.create(thirdInput, thirdKey).caseId()).isEqualTo(third.caseId());
        assertThatThrownBy(() -> service.create(new CaseDto.Create(EVENT, Case.Type.FAN_REPORT,
                List.of(), List.of(reportId), List.of(), "Changed follow-up reason", second.caseId(),
                "New report details require a separate review"), thirdKey))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        assertThat(cases.count()).isEqualTo(3);
    }

    @Test
    void activeFollowUpPreventsAnotherCaseFromReusingTheSource() {
        UUID reportId = report(TENANT, EVENT).getFanReportId();
        CaseDto.View first = service.create(createInput(reportId), UUID.randomUUID().toString());
        assertThatThrownBy(() -> service.create(followUpInput(reportId, first.caseId()),
                UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("CASE_STATE_CONFLICT"));
        dismissThroughReviewer(first);
        CaseDto.View activeSecond = service.create(followUpInput(reportId, first.caseId()),
                UUID.randomUUID().toString());
        long caseCount = cases.count();
        long sourceCount = sources.count();
        long activityCount = activities.count();

        assertThatThrownBy(() -> service.create(followUpInput(reportId, first.caseId()),
                UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("SOURCE_ALREADY_LINKED"));
        assertThat(cases.findById(activeSecond.caseId()).orElseThrow().getStatus()).isEqualTo(Case.Status.OPEN);
        assertThat(cases.count()).isEqualTo(caseCount);
        assertThat(sources.count()).isEqualTo(sourceCount);
        assertThat(activities.count()).isEqualTo(activityCount);
    }

    @Test
    void unrelatedTerminalCaseCannotAuthorizeReusedSource() {
        UUID reportId = report(TENANT, EVENT).getFanReportId();
        UUID unrelatedReportId = report(TENANT, EVENT).getFanReportId();
        CaseDto.View first = service.create(createInput(reportId), UUID.randomUUID().toString());
        dismissThroughReviewer(first);
        CaseDto.View unrelated = service.create(createInput(unrelatedReportId), UUID.randomUUID().toString());
        dismissThroughReviewer(unrelated);
        long caseCount = cases.count();
        long sourceCount = sources.count();
        long activityCount = activities.count();

        assertThatThrownBy(() -> service.create(followUpInput(reportId, unrelated.caseId()),
                UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("SOURCE_ALREADY_LINKED"));
        assertThat(cases.count()).isEqualTo(caseCount);
        assertThat(sources.count()).isEqualTo(sourceCount);
        assertThat(activities.count()).isEqualTo(activityCount);
    }

    @Test
    void followUpRejectsRelatedCasesFromAnotherTenantOrEvent() {
        UUID reportId = report(TENANT, EVENT).getFanReportId();
        UUID otherTenantMember = UUID.randomUUID();
        memberships.save(new AdminMembershipEntity(otherTenantMember, OTHER_TENANT,
                "other-tenant-member", AccessRecordStatus.ACTIVE));
        Case otherTenantCase = new Case(UUID.randomUUID(), OTHER_TENANT, OTHER_EVENT,
                otherTenantMember, Case.Type.FAN_REPORT, null, null, LocalDateTime.now());
        otherTenantCase.transition(Case.Status.DISMISSED, null, LocalDateTime.now());
        cases.saveAndFlush(otherTenantCase);
        UUID sameTenantOtherEvent = UUID.randomUUID();
        events.save(new TenantEvent(sameTenantOtherEvent, TENANT, "second-event", "Second event",
                AccessRecordStatus.ACTIVE));
        Case otherEventCase = new Case(UUID.randomUUID(), TENANT, sameTenantOtherEvent,
                ANALYST, Case.Type.FAN_REPORT, null, null, LocalDateTime.now());
        otherEventCase.transition(Case.Status.DISMISSED, null, LocalDateTime.now());
        cases.saveAndFlush(otherEventCase);
        long caseCount = cases.count();
        long sourceCount = sources.count();
        long activityCount = activities.count();

        for (UUID relatedId : List.of(otherTenantCase.getId(), otherEventCase.getId())) {
            assertThatThrownBy(() -> service.create(followUpInput(reportId, relatedId),
                    UUID.randomUUID().toString()))
                    .isInstanceOfSatisfying(CaseApiException.class,
                            error -> assertThat(error.code()).isEqualTo("RESOURCE_NOT_FOUND"));
        }
        assertThat(cases.count()).isEqualTo(caseCount);
        assertThat(sources.count()).isEqualTo(sourceCount);
        assertThat(activities.count()).isEqualTo(activityCount);
    }

    private CaseDto.Create followUpInput(UUID reportId, UUID relatedCaseId) {
        return new CaseDto.Create(EVENT, Case.Type.FAN_REPORT, List.of(), List.of(reportId), List.of(),
                "Investigate new synthetic information", relatedCaseId,
                "New report details require a separate review");
    }

    private CaseDto.View dismissThroughReviewer(CaseDto.View created) {
        as("reviewer");
        CaseDto.View assigned = service.assign(created.caseId(),
                new CaseDto.Assign(ANALYST, created.caseVersion(), "Assign for human review"),
                UUID.randomUUID().toString());
        CaseDto.View triaged = service.transition(created.caseId(), new CaseDto.Transition(
                Case.Status.TRIAGED, assigned.caseVersion(), "Classify the report",
                "Synthetic listing", null, null, null, null, null), UUID.randomUUID().toString());
        CaseDto.View reviewing = service.transition(created.caseId(), new CaseDto.Transition(
                Case.Status.IN_REVIEW, triaged.caseVersion(), "Start human review",
                null, null, null, null, null, null), UUID.randomUUID().toString());
        return service.transition(created.caseId(), new CaseDto.Transition(
                Case.Status.DISMISSED, reviewing.caseVersion(),
                "Reviewed the synthetic listing and found no violation", null, null,
                null, null, null, CaseDto.DismissalBasis.NO_VIOLATION_FOUND_AFTER_REVIEW),
                UUID.randomUUID().toString());
    }

    @Test
    void reviewerAssignmentAndFiveTransitionsProduceAuditedVersions() {
        as("analyst");
        UUID reportId = report(TENANT, EVENT).getFanReportId();
        CaseDto.View created = service.create(createInput(reportId), UUID.randomUUID().toString());
        assertThatThrownBy(() -> service.assign(created.caseId(),
                new CaseDto.Assign(ANALYST, created.caseVersion(), "Unauthorized assignment"),
                UUID.randomUUID().toString()))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        as("reviewer");
        CaseDto.View assigned = service.assign(created.caseId(),
                new CaseDto.Assign(ANALYST, created.caseVersion(), "Assign for review"), UUID.randomUUID().toString());
        assertThat(assigned.caseVersion()).isEqualTo(created.caseVersion() + 1);
        assertThatThrownBy(() -> service.transition(created.caseId(), new CaseDto.Transition(
                Case.Status.TRIAGED, created.caseVersion(), "Stale transition", "Review listing",
                null, null, null, null, null), UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("CASE_VERSION_CONFLICT"));
        assertThat(service.activities(created.caseId(), 0, 20).items().stream()
                .filter(activity -> activity.eventType().equals("CASE_ASSIGNEE_CHANGED")))
                .singleElement().satisfies(activity ->
                        assertThat(activity.newAssigneeMembershipId()).isEqualTo(ANALYST));
        CaseDto.View triaged = service.transition(created.caseId(), new CaseDto.Transition(Case.Status.TRIAGED,
                assigned.caseVersion(), "Initial classification", "Review ticket listing", null,
                null, null, null, null), UUID.randomUUID().toString());
        CaseDto.View reviewing = service.transition(created.caseId(), new CaseDto.Transition(Case.Status.IN_REVIEW,
                triaged.caseVersion(), "Start human review", null, null,
                null, null, null, null), UUID.randomUUID().toString());
        CaseDto.View needed = service.transition(created.caseId(), new CaseDto.Transition(Case.Status.NEEDS_EVIDENCE,
                reviewing.caseVersion(), "Request supporting proof", null, null,
                "Missing listing capture", "Request screenshot", null, null), UUID.randomUUID().toString());
        CaseDto.View resumed = service.transition(created.caseId(), new CaseDto.Transition(Case.Status.IN_REVIEW,
                needed.caseVersion(), "Resume human review", null, null,
                null, null, "Reporter could not supply screenshot", null), UUID.randomUUID().toString());
        CaseDto.View dismissed = service.transition(created.caseId(), new CaseDto.Transition(Case.Status.DISMISSED,
                resumed.caseVersion(), "Reviewed original and no policy violation found", null, null,
                null, null, null, CaseDto.DismissalBasis.NO_VIOLATION_FOUND_AFTER_REVIEW),
                UUID.randomUUID().toString());
        assertThat(dismissed.status()).isEqualTo(Case.Status.DISMISSED);
        assertThat(dismissed.evidenceRevision()).isEqualTo(1);
        assertThat(activities.findByCaseId(created.caseId(), org.springframework.data.domain.Pageable.unpaged())
                .getTotalElements()).isEqualTo(8);
        assertThatThrownBy(() -> service.transition(created.caseId(), new CaseDto.Transition(
                Case.Status.APPROVED_FOR_REPORT, dismissed.caseVersion(), "Forbidden", null, null,
                null, null, null, null), UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("CASE_STATE_CONFLICT"));
    }
}
