package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.in.web.dto.ApprovalRequestDto;
import com.ticket.defender_core.adapter.in.web.dto.CaseDto;
import com.ticket.defender_core.adapter.in.web.dto.CasePreparationDto;
import com.ticket.defender_core.adapter.in.web.dto.EvidenceIntegrityDto;
import com.ticket.defender_core.adapter.out.persistence.*;
import com.ticket.defender_core.application.service.*;
import com.ticket.defender_core.domain.*;
import com.ticket.defender_core.security.AdminRole;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:approval-request;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false",
        "ticket-defender.evidence-preservation.enabled=true",
        "ticket-defender.evidence-preservation.max-bytes=20971520"
})
@AutoConfigureMockMvc
class ApprovalRequestApiIntegrationTest {
    private static final String PROFILE = "W08_TEST_LOCKED";
    private static final long MAX_BYTES = 20L * 1024 * 1024;

    @Autowired private CaseService caseService;
    @Autowired private CasePreparationService preparation;
    @Autowired private EvidenceIntegrityService integrity;
    @Autowired private EvidencePreservationService preservation;
    @Autowired private ApprovalRequestService approvals;
    @Autowired private CaseRepository cases;
    @Autowired private CaseSourceReferenceRepository sources;
    @Autowired private EvidenceSnapshotRepository snapshots;
    @Autowired private ReportVersionRepository reports;
    @Autowired private EvidencePreservationPolicyRepository policies;
    @Autowired private ApprovalRequestRepository requests;
    @MockitoSpyBean private ApprovalAuditEventRepository audits;
    @Autowired private IssuanceHoldRepository holds;
    @Autowired private CaseActivityRepository activities;
    @Autowired private CaseIdempotencyRepository idempotency;
    @Autowired private TenantRepository tenants;
    @Autowired private TenantEventRepository events;
    @Autowired private AdminMembershipRepository memberships;
    @Autowired private MembershipRoleRepository roles;
    @Autowired private MembershipEventGrantRepository grants;
    @Autowired private FanReportRepository fanReports;
    @Autowired private EvidenceAssetRepository assets;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @MockitoBean(name = "adminJwtDecoder") private JwtDecoder decoder;
    @MockitoBean private EvidenceObjectStorage objectStorage;
    @MockitoBean private EvidencePreservationStorage preservationStorage;

    private UUID tenant;
    private UUID event;
    private UUID reviewer;
    private String reviewerSubject;
    private String analystSubject;
    private String approverSubject;
    private String tenantAdminSubject;
    private String otherTenantReviewerSubject;
    private String otherEventReviewerSubject;

    @BeforeEach
    void setup() {
        reset(decoder, objectStorage, preservationStorage);
        tenant = UUID.randomUUID();
        event = UUID.randomUUID();
        reviewer = UUID.randomUUID();
        reviewerSubject = "w08-reviewer-" + reviewer;
        analystSubject = "w08-analyst-" + UUID.randomUUID();
        approverSubject = "w08-approver-" + UUID.randomUUID();
        tenantAdminSubject = "w08-admin-" + UUID.randomUUID();
        tenants.saveAndFlush(new Tenant(tenant, "Synthetic W08 tenant", AccessRecordStatus.ACTIVE));
        events.saveAndFlush(new TenantEvent(event, tenant, "w08-" + event,
                "Synthetic W08 event", AccessRecordStatus.ACTIVE));
        member(reviewer, reviewerSubject, AdminRole.REVIEWER, tenant, event);
        member(UUID.randomUUID(), analystSubject, AdminRole.ANALYST, tenant, event);
        member(UUID.randomUUID(), approverSubject, AdminRole.APPROVER, tenant, event);
        member(UUID.randomUUID(), tenantAdminSubject, AdminRole.TENANT_ADMIN, tenant, event);
        UUID otherEventInTenant = UUID.randomUUID();
        events.saveAndFlush(new TenantEvent(otherEventInTenant, tenant, "other-event-" + otherEventInTenant,
                "Other event in the synthetic tenant", AccessRecordStatus.ACTIVE));
        otherEventReviewerSubject = "other-event-w08-reviewer-" + UUID.randomUUID();
        member(UUID.randomUUID(), otherEventReviewerSubject, AdminRole.REVIEWER, tenant, otherEventInTenant);

        UUID otherTenant = UUID.randomUUID();
        UUID otherEvent = UUID.randomUUID();
        tenants.saveAndFlush(new Tenant(otherTenant, "Other synthetic tenant", AccessRecordStatus.ACTIVE));
        events.saveAndFlush(new TenantEvent(otherEvent, otherTenant, "other-" + otherEvent,
                "Other synthetic event", AccessRecordStatus.ACTIVE));
        otherTenantReviewerSubject = "other-w08-reviewer-" + UUID.randomUUID();
        member(UUID.randomUUID(), otherTenantReviewerSubject, AdminRole.REVIEWER, otherTenant, otherEvent);

        given(decoder.decode(anyString())).willAnswer(call -> jwt(call.getArgument(0)));
        given(preservationStorage.profile()).willReturn(PROFILE);
        authenticate(reviewerSubject);
    }

    @Test
    void submitPinsCurrentEvidenceReportTemplateAndCreatesAtomicPendingState() {
        Fixture fixture = fixture(true);
        long before = currentCase(fixture).getCaseVersion();

        ApprovalRequestDto.View result = approvals.submit(fixture.caseId(), command(fixture), fixture.key());

        Case target = currentCase(fixture);
        ReportVersion report = reports.findById(fixture.reportVersionId()).orElseThrow();
        assertThat(result.status()).isEqualTo(ApprovalRequest.Status.PENDING);
        assertThat(result.approvalEligibility()).isEqualTo("BLOCKED");
        assertThat(result.reportRevision()).isEqualTo(report.getRevision());
        assertThat(result.reportContentSha256()).isEqualTo(report.getContentSha256());
        assertThat(result.reportTemplateVersion()).isEqualTo("case-approval-input-v1");
        assertThat(result.reportTemplateSha256()).hasSize(64).isEqualTo(report.getTemplateSha256());
        assertThat(result.manifestSha256()).isEqualTo(fixture.manifestSha256());
        assertThat(result.requestedBy()).isEqualTo(reviewer);
        assertThat(target.getStatus()).isEqualTo(Case.Status.READY_FOR_APPROVAL);
        assertThat(target.getCurrentReportVersionId()).isEqualTo(report.getId());
        assertThat(target.getCaseVersion()).isEqualTo(before + 1);
        assertThat(result.caseVersionAtSubmit()).isEqualTo(target.getCaseVersion());
        assertThat(report.getStatus()).isEqualTo(ReportVersion.Status.PENDING_APPROVAL.name());
        assertThat(report.getVersion()).isEqualTo(1);
        assertThat(audits.findByApprovalRequestIdOrderByOccurredAtAscIdAsc(result.approvalRequestId()))
                .singleElement().extracting(ApprovalAuditEvent::getEventType)
                .isEqualTo("APPROVAL_REQUESTED");
        assertThat(caseActivityCount(fixture.caseId(), "APPROVAL_REQUESTED")).isEqualTo(1);
        assertThat(approvals.detail(result.approvalRequestId())).usingRecursiveComparison()
                .ignoringFields("requestedAt").isEqualTo(result);
    }

    @Test
    void postRequiresAuthenticationReviewerRoleAndHidesRequestAcrossTenants() throws Exception {
        Fixture fixture = fixture(true);
        String body = json.writeValueAsString(command(fixture));

        mvc.perform(post("/api/v1/cases/{caseId}/approval-requests", fixture.caseId())
                        .header("Idempotency-Key", fixture.key())
                        .contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/cases/{caseId}/approval-requests", fixture.caseId())
                        .header("Authorization", "Bearer " + analystSubject)
                        .header("Idempotency-Key", fixture.key())
                        .contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/cases/{caseId}/approval-requests", fixture.caseId())
                        .header("Authorization", "Bearer " + otherEventReviewerSubject)
                        .header("Idempotency-Key", fixture.key())
                        .contentType("application/json").content(body))
                .andExpect(status().isNotFound());
        assertThat(requests.findByReportVersionId(fixture.reportVersionId())).isEmpty();

        mvc.perform(post("/api/v1/cases/{caseId}/approval-requests", fixture.caseId())
                        .header("Authorization", "Bearer " + reviewerSubject)
                        .header("Idempotency-Key", fixture.key())
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.approvalEligibility").value("BLOCKED"))
                .andExpect(jsonPath("$.objectKey").doesNotExist());
        UUID requestId = requests.findByReportVersionId(fixture.reportVersionId()).orElseThrow().getId();
        mvc.perform(get("/api/v1/approval-requests/{id}", requestId)
                        .header("Authorization", "Bearer " + approverSubject))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/approval-requests/{id}", requestId)
                        .header("Authorization", "Bearer " + otherTenantReviewerSubject))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/approval-requests/{id}", requestId)
                        .header("Authorization", "Bearer " + otherEventReviewerSubject))
                .andExpect(status().isNotFound());
    }

    @Test
    void snapshotOrReportFromAnotherCaseIsHidden() {
        Fixture first = fixture(true);
        Fixture second = fixture(true);
        ApprovalRequestDto.Submit mismatchedSnapshot = new ApprovalRequestDto.Submit(
                first.reportVersionId(), second.snapshotId(), currentCase(first).getCaseVersion(),
                reports.findById(first.reportVersionId()).orElseThrow().getVersion(),
                first.reportContentSha256(), "Reject cross-case snapshot binding");

        assertCode(() -> approvals.submit(first.caseId(), mismatchedSnapshot, UUID.randomUUID().toString()),
                HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        ApprovalRequestDto.Submit mismatchedReport = new ApprovalRequestDto.Submit(
                second.reportVersionId(), first.snapshotId(), currentCase(first).getCaseVersion(),
                reports.findById(second.reportVersionId()).orElseThrow().getVersion(),
                second.reportContentSha256(), "Reject cross-case report binding");
        assertCode(() -> approvals.submit(first.caseId(), mismatchedReport, UUID.randomUUID().toString()),
                HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertThat(requests.findByReportVersionId(first.reportVersionId())).isEmpty();
        assertThat(requests.findByReportVersionId(second.reportVersionId())).isEmpty();
    }

    @Test
    void changedEvidenceRevisionRejectsStaleSnapshotAndReport() {
        Fixture fixture = fixture(true);
        jdbc.update("UPDATE cases SET evidence_revision=evidence_revision+1, case_version=case_version+1 WHERE id=?",
                fixture.caseId());
        ApprovalRequestDto.Submit command = command(fixture, currentCase(fixture).getCaseVersion(),
                reports.findById(fixture.reportVersionId()).orElseThrow().getVersion(),
                fixture.reportContentSha256(), "Reject stale evidence revision");

        assertCode(() -> approvals.submit(fixture.caseId(), command, UUID.randomUUID().toString()),
                HttpStatus.CONFLICT, "EVIDENCE_VERSION_MISMATCH");
    }

    @Test
    void recomputesSnapshotAndReportDigestsInsteadOfTrustingStoredOrClientHashes() {
        Fixture snapshotFixture = fixture(true);
        jdbc.update("UPDATE evidence_snapshots SET manifest_json='{}' WHERE id=?", snapshotFixture.snapshotId());
        assertCode(() -> approvals.submit(snapshotFixture.caseId(), command(snapshotFixture),
                        UUID.randomUUID().toString()), HttpStatus.CONFLICT, "EVIDENCE_REVIEW_MISMATCH");

        Fixture reportFixture = fixture(true);
        jdbc.update("UPDATE report_versions SET content_json='{}' WHERE id=?", reportFixture.reportVersionId());
        assertCode(() -> approvals.submit(reportFixture.caseId(), command(reportFixture),
                        UUID.randomUUID().toString()), HttpStatus.CONFLICT, "REPORT_CONTENT_MISMATCH");

        Fixture clientFixture = fixture(true);
        ApprovalRequestDto.Submit forged = command(clientFixture, currentCase(clientFixture).getCaseVersion(),
                reports.findById(clientFixture.reportVersionId()).orElseThrow().getVersion(),
                "f".repeat(64), "Reject forged report digest");
        assertCode(() -> approvals.submit(clientFixture.caseId(), forged, UUID.randomUUID().toString()),
                HttpStatus.CONFLICT, "REPORT_CONTENT_MISMATCH");
    }

    @Test
    void legacyOrChangedTemplateArtifactCannotBeSubmitted() {
        Fixture fixture = fixture(true);
        jdbc.update("UPDATE report_versions SET template_sha256=? WHERE id=?",
                "f".repeat(64), fixture.reportVersionId());

        assertCode(() -> approvals.submit(fixture.caseId(), command(fixture), UUID.randomUUID().toString()),
                HttpStatus.UNPROCESSABLE_ENTITY, "TEMPLATE_NOT_RETAINED");

        Fixture legacy = fixture(true);
        String legacyContent = reports.findById(legacy.reportVersionId()).orElseThrow().getContentJson()
                .replace("case-approval-input-v1", "draft-input-v1");
        String legacyContentSha256 = CaseSnapshotManifest.sha256(legacyContent);
        jdbc.update("UPDATE report_versions SET template_version='draft-input-v1', "
                        + "template_sha256=NULL, content_json=?, content_sha256=? WHERE id=?",
                legacyContent, legacyContentSha256, legacy.reportVersionId());
        ApprovalRequestDto.Submit legacyCommand = command(legacy, currentCase(legacy).getCaseVersion(),
                reports.findById(legacy.reportVersionId()).orElseThrow().getVersion(),
                legacyContentSha256, "Reject legacy report input schema");
        assertCode(() -> approvals.submit(legacy.caseId(), legacyCommand, UUID.randomUUID().toString()),
                HttpStatus.UNPROCESSABLE_ENTITY, "TEMPLATE_NOT_RETAINED");
    }

    @Test
    void missingPreservationOrDraftOrRevokedPolicyBlocksRequest() {
        Fixture missing = fixture(false);
        assertCode(() -> approvals.submit(missing.caseId(), command(missing), UUID.randomUUID().toString()),
                HttpStatus.UNPROCESSABLE_ENTITY, "APPROVAL_BASIS_BLOCKED");

        Fixture draft = fixture(true);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        policies.saveAndFlush(new EvidencePreservationPolicy(draft.policyId(), 2, tenant, event,
                EvidencePreservationPolicy.Status.DRAFT, now.minusDays(1), now.plusDays(20),
                10, PROFILE, EvidencePreservationPolicy.Mode.GOVERNANCE,
                null, now, null, null));
        assertCode(() -> approvals.submit(draft.caseId(), command(draft), UUID.randomUUID().toString()),
                HttpStatus.UNPROCESSABLE_ENTITY, "APPROVAL_BASIS_BLOCKED");

        Fixture revoked = fixture(true);
        now = LocalDateTime.now(ZoneOffset.UTC);
        policies.saveAndFlush(new EvidencePreservationPolicy(revoked.policyId(), 2, tenant, event,
                EvidencePreservationPolicy.Status.REVOKED, now.minusDays(1), now.plusDays(20),
                10, PROFILE, EvidencePreservationPolicy.Mode.GOVERNANCE,
                "synthetic-authority", now.minusHours(2), now.minusHours(1), now));
        assertCode(() -> approvals.submit(revoked.caseId(), command(revoked), UUID.randomUUID().toString()),
                HttpStatus.UNPROCESSABLE_ENTITY, "APPROVAL_BASIS_BLOCKED");
    }

    @Test
    void activeIssuanceHoldBlocksSubmissionWithoutPartialState() {
        Fixture fixture = fixture(true);
        Case before = currentCase(fixture);
        holds.saveAndFlush(new IssuanceHold(before, "SYNTHETIC_REVIEW_REQUIRED", reviewer,
                LocalDateTime.now(ZoneOffset.UTC)));

        assertCode(() -> approvals.submit(fixture.caseId(), command(fixture), UUID.randomUUID().toString()),
                HttpStatus.CONFLICT, "ISSUANCE_HOLD_ACTIVE");
        assertThat(requests.findByReportVersionId(fixture.reportVersionId())).isEmpty();
        assertThat(reports.findById(fixture.reportVersionId()).orElseThrow().getStatus()).isEqualTo("DRAFT");
        assertThat(currentCase(fixture).getStatus()).isEqualTo(Case.Status.IN_REVIEW);
    }

    @Test
    void sameIdempotencyKeyReplaysAndDifferentBodyOrNewKeyCannotDuplicatePendingRequest() {
        Fixture fixture = fixture(true);
        ApprovalRequestDto.Submit command = command(fixture);
        ApprovalRequestDto.View first = approvals.submit(fixture.caseId(), command, fixture.key());
        ApprovalRequestDto.View replay = approvals.submit(fixture.caseId(), command, fixture.key());
        assertThat(replay).usingRecursiveComparison().ignoringFields("requestedAt").isEqualTo(first);
        assertThat(audits.findByApprovalRequestIdOrderByOccurredAtAscIdAsc(first.approvalRequestId())).hasSize(1);
        assertThat(caseActivityCount(fixture.caseId(), "APPROVAL_REQUESTED")).isEqualTo(1);

        ApprovalRequestDto.Submit changed = new ApprovalRequestDto.Submit(command.reportVersionId(),
                command.evidenceSnapshotId(), command.expectedCaseVersion(), command.expectedReportVersion(),
                command.expectedReportContentSha256(), "Different idempotent request body");
        assertCode(() -> approvals.submit(fixture.caseId(), changed, fixture.key()),
                HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED");

        ApprovalRequestDto.Submit latest = command(fixture, currentCase(fixture).getCaseVersion(),
                reports.findById(fixture.reportVersionId()).orElseThrow().getVersion(),
                fixture.reportContentSha256(), "Attempt duplicate active request");
        assertCode(() -> approvals.submit(fixture.caseId(), latest, UUID.randomUUID().toString()),
                HttpStatus.CONFLICT, "APPROVAL_REQUEST_ALREADY_PENDING");
        assertThat(requests.findByReportVersionId(fixture.reportVersionId())).isPresent();
    }

    @Test
    void staleCaseAndReportVersionsAreRejected() {
        Fixture caseFixture = fixture(true);
        ApprovalRequestDto.Submit staleCase = command(caseFixture,
                currentCase(caseFixture).getCaseVersion() - 1,
                reports.findById(caseFixture.reportVersionId()).orElseThrow().getVersion(),
                caseFixture.reportContentSha256(), "Reject stale Case version");
        assertCode(() -> approvals.submit(caseFixture.caseId(), staleCase, UUID.randomUUID().toString()),
                HttpStatus.CONFLICT, "CASE_VERSION_CONFLICT");

        Fixture reportFixture = fixture(true);
        ApprovalRequestDto.Submit staleReport = command(reportFixture,
                currentCase(reportFixture).getCaseVersion(), 99L,
                reportFixture.reportContentSha256(), "Reject stale report version");
        assertCode(() -> approvals.submit(reportFixture.caseId(), staleReport, UUID.randomUUID().toString()),
                HttpStatus.CONFLICT, "REPORT_VERSION_CONFLICT");
    }

    @Test
    void auditInsertFailureRollsBackRequestCaseReportActivityAndIdempotency() {
        Fixture fixture = fixture(true);
        long caseVersion = currentCase(fixture).getCaseVersion();
        String key = UUID.randomUUID().toString();
        doThrow(new DataAccessResourceFailureException("synthetic W08 audit failure"))
                .when(audits).saveAndFlush(any(ApprovalAuditEvent.class));

        assertThatThrownBy(() -> approvals.submit(fixture.caseId(), command(fixture), key))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(requests.findByReportVersionId(fixture.reportVersionId())).isEmpty();
        assertThat(audits.findAll()).noneMatch(event -> event.getCaseId().equals(fixture.caseId()));
        assertThat(caseActivityCount(fixture.caseId(), "APPROVAL_REQUESTED")).isZero();
        assertThat(currentCase(fixture).getStatus()).isEqualTo(Case.Status.IN_REVIEW);
        assertThat(currentCase(fixture).getCurrentReportVersionId()).isNull();
        assertThat(currentCase(fixture).getCaseVersion()).isEqualTo(caseVersion);
        assertThat(reports.findById(fixture.reportVersionId()).orElseThrow().getStatus()).isEqualTo("DRAFT");
        assertThat(reports.findById(fixture.reportVersionId()).orElseThrow().getVersion()).isZero();
        assertThat(idempotency.findByTenantIdAndActorMembershipIdAndOperationAndTargetCaseIdAndKeyDigest(
                tenant, reviewer, "APPROVAL_REQUEST_CREATE", fixture.caseId(),
                CaseSnapshotManifest.sha256(key))).isEmpty();
    }

    @Test
    void sealedReportColumnsCannotBeChangedThroughJpaAndPendingBlocksNewRevision() {
        Fixture fixture = fixture(true);
        String originalContent = reports.findById(fixture.reportVersionId()).orElseThrow().getContentJson();
        ReportVersion detached = reports.findById(fixture.reportVersionId()).orElseThrow();
        ReflectionTestUtils.setField(detached, "contentJson", "{\"tampered\":true}");
        ReflectionTestUtils.setField(detached, "templateVersion", "tampered-template");
        reports.saveAndFlush(detached);
        assertThat(jdbc.queryForObject("SELECT content_json FROM report_versions WHERE id=?",
                String.class, fixture.reportVersionId())).isEqualTo(originalContent);
        assertThat(jdbc.queryForObject("SELECT template_version FROM report_versions WHERE id=?",
                String.class, fixture.reportVersionId())).isEqualTo("case-approval-input-v1");

        approvals.submit(fixture.caseId(), command(fixture), UUID.randomUUID().toString());
        Case target = currentCase(fixture);
        CasePreparationDto.DraftInput revised = new CasePreparationDto.DraftInput(
                target.getCaseVersion(), fixture.snapshotId(), "Revised title",
                "Revised synthetic assessment for approval", "Synthetic limitations",
                "Synthetic conclusion", "Attempt revision while request pending");
        assertCode(() -> preparation.draft(fixture.caseId(), revised, UUID.randomUUID().toString()),
                HttpStatus.CONFLICT, "CASE_STATE_CONFLICT");
    }

    @Test
    void legacyOfficialPdfEndpointRemainsBlocked() throws Exception {
        mvc.perform(post("/api/v1/audits/1/report")
                        .header("Authorization", "Bearer " + reviewerSubject))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUDIT_REPORT_ISSUANCE_DISABLED"));
    }

    private Fixture fixture(boolean preserve) {
        UUID fanReportId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        FanReport fanReport = new FanReport(fanReportId, "FR-" + fanReportId, tenant, event,
                now.plusDays(30), null, FanReport.ReportType.OTHER, "Synthetic W08 evidence",
                null, null, null, null, now, UUID.randomUUID().toString(),
                "d".repeat(64), "e".repeat(64));
        fanReport.markReceived();
        fanReports.saveAndFlush(fanReport);
        String objectKey = "fan-reports/" + fanReportId + "/" + evidenceId;
        String sha256 = "a".repeat(64);
        EvidenceAsset asset = new EvidenceAsset(evidenceId, fanReportId, objectKey, "image/png",
                4, sha256, now, fanReport.getRetentionUntil());
        asset.markStored("etag-" + evidenceId);
        assets.saveAndFlush(asset);
        given(objectStorage.head(objectKey)).willReturn(
                new EvidenceObjectStorage.ObjectMetadata(4, "image/png", asset.getObjectVersion()));
        given(objectStorage.verifyBytes(objectKey, asset.getObjectVersion(), MAX_BYTES)).willReturn(
                new EvidenceObjectStorage.ByteVerification(4, "image/png", sha256,
                        asset.getObjectVersion(), EvidenceObjectStorage.MarkerKind.ETAG));

        authenticate(reviewerSubject);
        CaseDto.View opened = caseService.create(new CaseDto.Create(event, Case.Type.FAN_REPORT,
                List.of(), List.of(fanReportId), List.of(evidenceId),
                "Investigate synthetic approval evidence", null, null), UUID.randomUUID().toString());
        CaseDto.View assigned = caseService.assign(opened.caseId(),
                new CaseDto.Assign(reviewer, opened.caseVersion(), "Assign synthetic reviewer"),
                UUID.randomUUID().toString());
        CaseDto.View triaged = caseService.transition(opened.caseId(), new CaseDto.Transition(
                Case.Status.TRIAGED, assigned.caseVersion(), "Triage synthetic Case",
                "Synthetic approval classification", null, null, null, null, null),
                UUID.randomUUID().toString());
        CaseDto.View reviewing = caseService.transition(opened.caseId(), new CaseDto.Transition(
                Case.Status.IN_REVIEW, triaged.caseVersion(), "Begin synthetic review",
                null, null, null, null, null, null), UUID.randomUUID().toString());
        CasePreparationDto.Snapshot snapshot = preparation.capture(opened.caseId(),
                new CasePreparationDto.Capture(reviewing.caseVersion(), "Capture approval evidence"),
                UUID.randomUUID().toString());
        CasePreparationDto.Draft draft = preparation.draft(opened.caseId(),
                new CasePreparationDto.DraftInput(currentCase(opened.caseId()).getCaseVersion(),
                        snapshot.evidenceSnapshotId(), "Synthetic approval report",
                        "Synthetic assessment that is long enough for review",
                        "Synthetic limitations", "Synthetic conclusion", "Seal approval report"),
                UUID.randomUUID().toString());
        UUID sourceId = sources.findByCaseIdOrderByLinkedAtAscIdAsc(opened.caseId()).stream()
                .filter(source -> source.getSourceType() == CaseSourceReference.Type.EVIDENCE_ASSET)
                .findFirst().orElseThrow().getId();
        EvidenceIntegrityDto.Verification verification = integrity.verify(opened.caseId(),
                new EvidenceIntegrityDto.VerifyInput(currentCase(opened.caseId()).getCaseVersion(),
                        snapshot.evidenceSnapshotId(), sourceId, evidenceId,
                        "Verify approval evidence bytes"), UUID.randomUUID().toString());
        UUID policyId = UUID.randomUUID();
        policies.saveAndFlush(new EvidencePreservationPolicy(policyId, 1, tenant, event,
                EvidencePreservationPolicy.Status.APPROVED, now.minusDays(1), now.plusDays(20),
                10, PROFILE, EvidencePreservationPolicy.Mode.GOVERNANCE,
                "synthetic-authority", now.minusHours(2), now.minusHours(1), null));
        if (preserve) {
            given(preservationStorage.verifyLocked(anyString(), isNull(), eq(sha256), eq(4L), eq(MAX_BYTES)))
                    .willReturn(new EvidencePreservationStorage.LockedObject("locked-" + evidenceId,
                            sha256, 4, EvidencePreservationPolicy.Mode.GOVERNANCE, now.plusDays(5)));
            preservation.preserve(opened.caseId(), new EvidencePreservationService.Command(
                    currentCase(opened.caseId()).getCaseVersion(), snapshot.evidenceSnapshotId(), sourceId,
                    evidenceId, verification.recordId(), policyId, "Verify immutable approval evidence"),
                    UUID.randomUUID().toString());
        }
        return new Fixture(opened.caseId(), snapshot.evidenceSnapshotId(), draft.reportVersionId(),
                snapshot.manifestSha256(), draft.contentSha256(), policyId, UUID.randomUUID().toString());
    }

    private ApprovalRequestDto.Submit command(Fixture fixture) {
        ReportVersion report = reports.findById(fixture.reportVersionId()).orElseThrow();
        return command(fixture, currentCase(fixture).getCaseVersion(), report.getVersion(),
                fixture.reportContentSha256(), "Request independent approval review");
    }

    private ApprovalRequestDto.Submit command(Fixture fixture, long caseVersion, long reportVersion,
            String contentSha256, String reason) {
        return new ApprovalRequestDto.Submit(fixture.reportVersionId(), fixture.snapshotId(),
                caseVersion, reportVersion, contentSha256, reason);
    }

    private Case currentCase(Fixture fixture) {
        return currentCase(fixture.caseId());
    }

    private Case currentCase(UUID caseId) {
        return cases.findById(caseId).orElseThrow();
    }

    private long caseActivityCount(UUID caseId, String eventType) {
        return activities.findByCaseId(caseId, org.springframework.data.domain.Pageable.unpaged()).stream()
                .filter(activity -> eventType.equals(activity.getEventType())).count();
    }

    private void member(UUID id, String subject, AdminRole role, UUID memberTenant, UUID memberEvent) {
        memberships.saveAndFlush(new AdminMembershipEntity(id, memberTenant, subject, AccessRecordStatus.ACTIVE));
        roles.saveAndFlush(new MembershipRole(UUID.randomUUID(), id, role));
        grants.saveAndFlush(new MembershipEventGrant(UUID.randomUUID(), id, memberTenant, memberEvent));
    }

    private static void authenticate(String subject) {
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt(subject));
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private static Jwt jwt(String subject) {
        return Jwt.withTokenValue(subject).header("alg", "RS256").subject(subject).build();
    }

    private static void assertCode(Runnable action, HttpStatus status, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(CaseApiException.class, error -> {
            assertThat(error.status()).isEqualTo(status);
            assertThat(error.code()).isEqualTo(code);
        });
    }

    private record Fixture(UUID caseId, UUID snapshotId, UUID reportVersionId,
                           String manifestSha256, String reportContentSha256,
                           UUID policyId, String key) { }
}
