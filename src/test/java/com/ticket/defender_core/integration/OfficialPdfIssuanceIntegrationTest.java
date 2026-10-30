package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.in.web.dto.ApprovalRequestDto;
import com.ticket.defender_core.adapter.in.web.dto.ApprovalReviewDto;
import com.ticket.defender_core.adapter.in.web.dto.CaseDto;
import com.ticket.defender_core.adapter.in.web.dto.CasePreparationDto;
import com.ticket.defender_core.adapter.in.web.dto.EvidenceIntegrityDto;
import com.ticket.defender_core.adapter.in.web.dto.OfficialPdfDto;
import com.ticket.defender_core.adapter.out.persistence.*;
import com.ticket.defender_core.adapter.out.storage.S3EvidenceObjectStorage;
import com.ticket.defender_core.application.service.*;
import com.ticket.defender_core.domain.*;
import com.ticket.defender_core.security.AdminRole;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:official-pdf;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false",
        "ticket-defender.evidence-preservation.enabled=true",
        "ticket-defender.evidence-preservation.max-bytes=20971520",
        "ticket-defender.official-pdf.enabled=true",
        "ticket-defender.official-pdf.operational-mode=SYNTHETIC_ISOLATED"
})
class OfficialPdfIssuanceIntegrationTest {
    private static final String PROFILE = "W09_H2_LOCKED";
    private static final long MAX_BYTES = 20L * 1024 * 1024;

    @Autowired private CaseService caseService;
    @Autowired private CasePreparationService preparation;
    @Autowired private EvidenceIntegrityService integrity;
    @Autowired private EvidencePreservationService preservation;
    @Autowired private ApprovalRequestService approvals;
    @Autowired private ApprovalReviewService review;
    @Autowired private OfficialPdfIssuanceService official;
    @Autowired private CaseRepository cases;
    @Autowired private CaseSourceReferenceRepository sources;
    @Autowired private ReportVersionRepository reports;
    @Autowired private ApprovalRequestRepository requests;
    @Autowired private ApprovalDecisionRepository decisions;
    @Autowired private OfficialPdfIssuanceRepository issuances;
    @Autowired private OfficialPdfArtifactRepository artifacts;
    @Autowired private OfficialPdfIssuanceAttemptRepository attempts;
    @Autowired private OfficialPdfAuditEventRepository audits;
    @Autowired private IssuanceHoldRepository holds;
    @Autowired private EvidencePreservationPolicyRepository policies;
    @Autowired private TenantRepository tenants;
    @Autowired private TenantEventRepository events;
    @Autowired private AdminMembershipRepository memberships;
    @Autowired private MembershipRoleRepository roles;
    @Autowired private MembershipEventGrantRepository grants;
    @Autowired private FanReportRepository fanReports;
    @Autowired private EvidenceAssetRepository assets;
    @MockitoBean private EvidenceObjectStorage objectStorage;
    @MockitoBean private EvidencePreservationStorage preservationStorage;
    @MockitoBean private OfficialPdfStorage officialStorage;
    @MockitoSpyBean private OfficialPdfRenderer renderer;
    @MockitoSpyBean private OfficialPdfIssuanceWriteService writer;
    @MockitoSpyBean private CaseActivityRepository activities;
    @Autowired private InternalIssuanceHoldWriteService holdWriter;

    private final Map<UUID, Stored> stored = new ConcurrentHashMap<>();
    private UUID tenant;
    private UUID event;
    private UUID reviewer;
    private String reviewerSubject;
    private String approverSubject;
    private String adminSubject;
    private UUID adminMemberId;

    @BeforeEach
    void setup() {
        reset(objectStorage, preservationStorage, officialStorage, renderer, writer, activities);
        lease(Duration.ofMinutes(5));
        stored.clear();
        tenant = UUID.randomUUID();
        event = UUID.randomUUID();
        reviewer = UUID.randomUUID();
        reviewerSubject = "w09-reviewer-" + reviewer;
        approverSubject = "w09-approver-" + UUID.randomUUID();
        adminSubject = "w09-admin-" + UUID.randomUUID();
        tenants.saveAndFlush(new Tenant(tenant, "Synthetic W09 tenant", AccessRecordStatus.ACTIVE));
        events.saveAndFlush(new TenantEvent(event, tenant, "w09-" + event, "Synthetic W09 event", AccessRecordStatus.ACTIVE));
        member(reviewer, reviewerSubject, AdminRole.REVIEWER);
        member(UUID.randomUUID(), approverSubject, AdminRole.APPROVER);
        adminMemberId = UUID.randomUUID();
        member(adminMemberId, adminSubject, AdminRole.TENANT_ADMIN);
        given(preservationStorage.profile()).willReturn(PROFILE);
        given(officialStorage.putIfAbsent(any(UUID.class), anyString(), any(byte[].class))).willAnswer(call -> {
            UUID issuanceId = call.getArgument(0);
            byte[] bytes = call.getArgument(2);
            Stored value = stored.computeIfAbsent(issuanceId,
                    ignored -> new Stored(bytes, new OfficialPdfStorage.ObjectProof("v-" + issuanceId,
                            sha256(bytes), bytes.length)));
            return value.proof();
        });
        given(officialStorage.verify(any(UUID.class), anyString(), anyString(), anyString(), anyLong()))
                .willAnswer(call -> stored.get(call.getArgument(0)).proof());
        given(officialStorage.download(any(UUID.class), anyString(), anyString(), anyString(), anyLong()))
                .willAnswer(call -> {
                    Stored value = stored.get(call.getArgument(0));
                    return new OfficialPdfStorage.DownloadedPdf(value.bytes(), value.proof().sha256(), value.proof().size());
                });
    }

    @Test
    void issuesKoreanPdfForApprovedFrozenReportAndKeepsW08bReportStatus() throws Exception {
        Fixture fixture = approvedFixture();
        authenticate(adminSubject);

        String requestKey = UUID.randomUUID().toString();
        OfficialPdfDto.Issuance issued = official.request(fixture.decisionId(), requestKey);
        OfficialPdfDto.Issuance replayed = official.request(fixture.decisionId(), requestKey);

        assertThat(issued.status()).isEqualTo(OfficialPdfIssuance.Status.GENERATED);
        assertThat(replayed.officialPdfIssuanceId()).isEqualTo(issued.officialPdfIssuanceId());
        assertThat(replayed.artifactId()).isEqualTo(issued.artifactId());
        assertThat(issued.eligibility()).isEqualTo(OfficialPdfEligibilityValidator.State.CURRENT);
        assertThat(issued.artifactId()).isNotNull();
        assertThat(reports.findById(fixture.reportVersionId()).orElseThrow().getStatus())
                .isEqualTo(ReportVersion.Status.APPROVED.name());
        assertThat(artifacts.findByIssuanceId(issued.officialPdfIssuanceId())).isPresent();
        assertThat(attempts.findByIssuanceIdOrderByAttemptNumberAsc(issued.officialPdfIssuanceId()))
                .singleElement().extracting(OfficialPdfIssuanceAttempt::getStatus)
                .isEqualTo(OfficialPdfIssuanceAttempt.Status.SUCCEEDED);
        assertThat(audits.findByIssuanceIdOrderByOccurredAtAscIdAsc(issued.officialPdfIssuanceId()))
                .extracting(OfficialPdfAuditEvent::getEventType)
                .contains("ISSUANCE_RESERVED", "OBJECT_STORED", "ISSUANCE_GENERATED");

        OfficialPdfIssuanceService.Download download = official.download(issued.officialPdfIssuanceId());
        assertThat(download.bytes()).startsWith((byte) '%', (byte) 'P', (byte) 'D', (byte) 'F', (byte) '-');
        try (var document = Loader.loadPDF(download.bytes())) {
            String text = new PDFTextStripper().getText(document);
            // PDFBox preserves Korean glyphs; its extraction normalizes some embedded-font spaces to '?'.
            assertThat(text).contains("공식", "검토", "보고서", "합성", "승인", "증거", "W06", "W07", "확정", "사실");
        }
        verify(officialStorage, times(1)).putIfAbsent(eq(issued.officialPdfIssuanceId()), anyString(), any(byte[].class));
        assertThat(audits.findByIssuanceIdOrderByOccurredAtAscIdAsc(issued.officialPdfIssuanceId()))
                .extracting(OfficialPdfAuditEvent::getEventType).contains("ARTIFACT_DOWNLOADED");
    }

    @Test
    void activeHoldAndChangedEvidenceAreNotTreatedAsHistoricalDecisionChanges() {
        Fixture holdFixture = approvedFixture();
        Case target = cases.findById(holdFixture.caseId()).orElseThrow();
        holds.saveAndFlush(new IssuanceHold(target, "SYNTHETIC_HOLD", reviewer, LocalDateTime.now(ZoneOffset.UTC)));
        authenticate(adminSubject);
        OfficialPdfDto.Eligibility heldEligibility = official.eligibility(holdFixture.decisionId());
        assertThat(heldEligibility.state()).isEqualTo(OfficialPdfEligibilityValidator.State.BLOCKED);
        assertThat(heldEligibility.code()).isEqualTo("ISSUANCE_HOLD_ACTIVE");
        assertThatThrownBy(() -> official.request(holdFixture.decisionId(), UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(CaseApiException.class, error -> {
                    assertThat(error.code()).isEqualTo("ISSUANCE_HOLD_ACTIVE");
                    assertThat(error.status()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
                });
        assertThat(decisions.findById(holdFixture.decisionId()).orElseThrow().getOutcome())
                .isEqualTo(ApprovalDecision.Outcome.APPROVE);

        Fixture staleFixture = approvedFixture();
        Case stale = cases.findById(staleFixture.caseId()).orElseThrow();
        ReflectionTestUtils.setField(stale, "evidenceRevision", stale.getEvidenceRevision() + 1);
        cases.saveAndFlush(stale);
        authenticate(adminSubject);
        OfficialPdfDto.Eligibility eligibility = official.eligibility(staleFixture.decisionId());
        assertThat(eligibility.state()).isEqualTo(OfficialPdfEligibilityValidator.State.STALE);
        assertThat(eligibility.code()).isEqualTo("APPROVAL_BASIS_STALE");
        assertThat(reports.findById(staleFixture.reportVersionId()).orElseThrow().getStatus())
                .isEqualTo(ReportVersion.Status.APPROVED.name());
    }

    @Test
    void renderingFailureLeavesNoArtifactThenRetryUsesOneIssuanceAndOneArtifact() {
        Fixture fixture = approvedFixture();
        authenticate(adminSubject);
        doThrow(new OfficialPdfRenderer.OfficialPdfRenderingException("synthetic renderer failure"))
                .doCallRealMethod().when(renderer).render(any());

        OfficialPdfDto.Issuance failed = official.request(fixture.decisionId(), UUID.randomUUID().toString());

        assertThat(failed.status()).isEqualTo(OfficialPdfIssuance.Status.FAILED);
        assertThat(artifacts.findByIssuanceId(failed.officialPdfIssuanceId())).isEmpty();
        assertThat(reports.findById(fixture.reportVersionId()).orElseThrow().getStatus())
                .isEqualTo(ReportVersion.Status.APPROVED.name());
        OfficialPdfDto.Issuance recovered = official.retry(failed.officialPdfIssuanceId(), UUID.randomUUID().toString());
        assertThat(recovered.status()).as("retry failure code: %s", recovered.lastFailureCode())
                .isEqualTo(OfficialPdfIssuance.Status.GENERATED);
        assertThat(issuances.findByApprovalDecisionId(fixture.decisionId())).hasValueSatisfying(
                issuance -> assertThat(issuance.getId()).isEqualTo(failed.officialPdfIssuanceId()));
        assertThat(artifacts.findAll().stream().filter(artifact -> fixture.decisionId()
                .equals(artifact.getApprovalDecisionId()))).hasSize(1);
    }

    @Test
    void storageFailureLeavesNoArtifactThenRetryCreatesOneVerifiedArtifact() {
        Fixture fixture = approvedFixture();
        authenticate(adminSubject);
        AtomicBoolean firstUpload = new AtomicBoolean(true);
        given(officialStorage.putIfAbsent(any(UUID.class), anyString(), any(byte[].class))).willAnswer(call -> {
            if (firstUpload.getAndSet(false)) throw new IllegalStateException("synthetic private storage failure");
            UUID issuanceId = call.getArgument(0);
            byte[] bytes = call.getArgument(2);
            return stored.computeIfAbsent(issuanceId, ignored -> new Stored(bytes,
                    new OfficialPdfStorage.ObjectProof("v-" + issuanceId, sha256(bytes), bytes.length))).proof();
        });

        OfficialPdfDto.Issuance failed = official.request(fixture.decisionId(), UUID.randomUUID().toString());

        assertThat(failed.status()).isEqualTo(OfficialPdfIssuance.Status.FAILED);
        assertThat(issuances.findById(failed.officialPdfIssuanceId()).orElseThrow().hasCandidateObject()).isFalse();
        assertThat(artifacts.findByIssuanceId(failed.officialPdfIssuanceId())).isEmpty();
        assertThat(reports.findById(fixture.reportVersionId()).orElseThrow().getStatus())
                .isEqualTo(ReportVersion.Status.APPROVED.name());

        OfficialPdfDto.Issuance recovered = official.retry(failed.officialPdfIssuanceId(), UUID.randomUUID().toString());

        assertThat(recovered.status()).isEqualTo(OfficialPdfIssuance.Status.GENERATED);
        assertThat(artifacts.findAll().stream().filter(artifact -> fixture.decisionId()
                .equals(artifact.getApprovalDecisionId()))).hasSize(1);
        assertThat(stored).containsOnlyKeys(failed.officialPdfIssuanceId());
    }

    @Test
    void finalizationDatabaseFailureKeepsOneCandidateThenRecoversWithoutSecondPdf() {
        Fixture fixture = approvedFixture();
        authenticate(adminSubject);
        AtomicBoolean firstFinalization = new AtomicBoolean(true);
        doAnswer(call -> {
            Object finalized = call.callRealMethod();
            if (firstFinalization.getAndSet(false))
                throw new DataIntegrityViolationException("synthetic official PDF finalization failure");
            return finalized;
        }).when(writer).finalizeIssuance(any(UUID.class), any(UUID.class), any(UUID.class),
                any(OfficialPdfStorage.ObjectProof.class));

        OfficialPdfDto.Issuance failed = official.request(fixture.decisionId(), UUID.randomUUID().toString());

        OfficialPdfIssuance candidate = issuances.findById(failed.officialPdfIssuanceId()).orElseThrow();
        assertThat(failed.status()).isEqualTo(OfficialPdfIssuance.Status.FAILED);
        assertThat(candidate.hasCandidateObject()).isTrue();
        assertThat(artifacts.findByIssuanceId(candidate.getId())).isEmpty();
        assertThat(attempts.findByIssuanceIdOrderByAttemptNumberAsc(candidate.getId()))
                .singleElement().extracting(OfficialPdfIssuanceAttempt::getStatus)
                .isEqualTo(OfficialPdfIssuanceAttempt.Status.FAILED);
        assertThat(audits.findByIssuanceIdOrderByOccurredAtAscIdAsc(candidate.getId()))
                .extracting(OfficialPdfAuditEvent::getEventType)
                .contains("OBJECT_STORED", "ISSUANCE_FAILED").doesNotContain("ISSUANCE_GENERATED");
        assertThat(reports.findById(fixture.reportVersionId()).orElseThrow().getStatus())
                .isEqualTo(ReportVersion.Status.APPROVED.name());
        assertThat(decisions.findById(fixture.decisionId()).orElseThrow().getOutcome())
                .isEqualTo(ApprovalDecision.Outcome.APPROVE);

        OfficialPdfDto.Issuance recovered = official.retry(candidate.getId(), UUID.randomUUID().toString());

        assertThat(recovered.status()).as("recovery failure code: %s", recovered.lastFailureCode())
                .isEqualTo(OfficialPdfIssuance.Status.GENERATED);
        assertThat(artifacts.findAll().stream().filter(artifact -> fixture.decisionId()
                .equals(artifact.getApprovalDecisionId()))).hasSize(1);
        assertThat(stored).containsOnlyKeys(candidate.getId());
        verify(officialStorage, times(1)).putIfAbsent(eq(candidate.getId()), anyString(), any(byte[].class));
    }

    @Test
    void retryDuringLiveAttemptIsRejectedAndExpiredLeaseFencesTheOldWorker() {
        Fixture fixture = approvedFixture();
        authenticate(adminSubject);
        AtomicBoolean firstRender = new AtomicBoolean(true);
        Map<String, Object> observed = new ConcurrentHashMap<>();
        doAnswer(call -> {
            if (firstRender.getAndSet(false)) {
                UUID issuanceId = issuances.findByApprovalDecisionId(fixture.decisionId()).orElseThrow().getId();
                try {
                    official.retry(issuanceId, UUID.randomUUID().toString());
                } catch (CaseApiException live) {
                    observed.put("live", live);
                }
                lease(Duration.ZERO);
                observed.put("takeover", official.retry(issuanceId, UUID.randomUUID().toString()));
            }
            return call.callRealMethod();
        }).when(renderer).render(any());

        OfficialPdfDto.Issuance superseded = official.request(fixture.decisionId(), UUID.randomUUID().toString());

        assertThat(observed.get("live")).isInstanceOfSatisfying(CaseApiException.class, error -> {
            assertThat(error.status()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
            assertThat(error.code()).isEqualTo("OFFICIAL_PDF_ISSUANCE_IN_PROGRESS");
        });
        assertThat(((OfficialPdfDto.Issuance) observed.get("takeover")).status())
                .isEqualTo(OfficialPdfIssuance.Status.GENERATED);
        assertThat(superseded.status()).isEqualTo(OfficialPdfIssuance.Status.GENERATED);
        verify(renderer, times(2)).render(any());
        assertThat(attempts.findByIssuanceIdOrderByAttemptNumberAsc(superseded.officialPdfIssuanceId()))
                .extracting(OfficialPdfIssuanceAttempt::getStatus, OfficialPdfIssuanceAttempt::getFailureCode)
                .containsExactly(tuple(OfficialPdfIssuanceAttempt.Status.FAILED, "RECOVERY_REPLACED"),
                        tuple(OfficialPdfIssuanceAttempt.Status.SUCCEEDED, null));
        assertThat(artifacts.findAll().stream().filter(artifact -> fixture.decisionId()
                .equals(artifact.getApprovalDecisionId()))).hasSize(1);
        assertThat(audits.findByIssuanceIdOrderByOccurredAtAscIdAsc(superseded.officialPdfIssuanceId()))
                .extracting(OfficialPdfAuditEvent::getEventType)
                .doesNotContain("ISSUANCE_FAILED", "ISSUANCE_STALE")
                .containsOnlyOnce("ISSUANCE_GENERATED", "OBJECT_STORED");
    }

    @Test
    void pinnedTemplateRenderIsByteDeterministicForRecoveryAndShowsSealedIdentifiers() throws Exception {
        Fixture fixture = approvedFixture();
        authenticate(adminSubject);
        OfficialPdfDto.Issuance issued = official.request(fixture.decisionId(), UUID.randomUUID().toString());
        OfficialPdfIssuanceWriteService.RenderSource source = writer.renderSource(issued.officialPdfIssuanceId(),
                adminMemberId);
        OfficialPdfRenderer.RenderContext context = new OfficialPdfRenderer.RenderContext(source.template(),
                source.issuance(), source.report(), source.decision(), source.request(), source.snapshot(),
                source.evidenceSourceCount());

        byte[] first = renderer.render(context);
        byte[] second = renderer.render(context);

        assertThat(sha256(second)).isEqualTo(sha256(first))
                .isEqualTo(artifacts.findByIssuanceId(issued.officialPdfIssuanceId()).orElseThrow().getPdfSha256());
        assertThat(source.template().sha256()).isEqualTo(source.issuance().getOfficialTemplateSha256());
        try (var document = Loader.loadPDF(first)) {
            String text = new PDFTextStripper().getText(document).replaceAll("\\s+", "");
            assertThat(text).contains(fixture.caseId().toString(), fixture.reportVersionId().toString(), "KST",
                    source.request().getRequestedByMembershipId().toString(),
                    source.decision().getDecidedByMembershipId().toString());
        }
        assertThatThrownBy(() -> new OfficialPdfTemplateRegistry().pinned("official-case-pdf-v1", "0".repeat(64)))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("OFFICIAL_TEMPLATE_CHANGED"));
    }

    @Test
    void internalHoldWritesAreAuditedAtomicallyAndBlockIssuance() {
        Fixture fixture = approvedFixture();
        doThrow(new DataIntegrityViolationException("synthetic hold audit failure")).when(activities)
                .saveAndFlush(argThat(activity -> activity instanceof CaseActivity value
                        && "ISSUANCE_HOLD_PLACED".equals(value.getEventType())
                        && value.getReason().contains("SYNTHETIC_AUDIT_FAILURE")));
        assertThatThrownBy(() -> holdWriter.place(fixture.caseId(), reviewer, "SYNTHETIC_AUDIT_FAILURE"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(holds.existsByTenantIdAndEventIdAndCaseIdAndStatus(tenant, event, fixture.caseId(),
                IssuanceHold.Status.ACTIVE)).isFalse();

        IssuanceHold hold = holdWriter.place(fixture.caseId(), reviewer, "SYNTHETIC_HOLD");
        authenticate(adminSubject);
        assertThatThrownBy(() -> official.request(fixture.decisionId(), UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("ISSUANCE_HOLD_ACTIVE"));
        holdWriter.release(hold.getId(), reviewer);
        assertThatThrownBy(() -> holdWriter.release(hold.getId(), reviewer)).isInstanceOf(IllegalStateException.class);

        assertThat(holdActivities(fixture.caseId())).extracting(CaseActivity::getEventType, CaseActivity::getActorRole)
                .containsExactly(tuple("ISSUANCE_HOLD_PLACED", "SYSTEM"), tuple("ISSUANCE_HOLD_RELEASED", "SYSTEM"));
        assertThat(holdActivities(fixture.caseId())).allSatisfy(activity -> assertThat(activity.getReason())
                .contains("issuanceHold=" + hold.getId()));
        assertThat(decisions.findById(fixture.decisionId()).orElseThrow().getOutcome())
                .isEqualTo(ApprovalDecision.Outcome.APPROVE);
    }

    @Test
    void changedOriginalEvidenceObjectBlocksIssuanceBeforeCreatingWorkRecord() {
        Fixture fixture = approvedFixture();
        EvidenceAsset asset = assets.findById(fixture.evidenceId()).orElseThrow();
        authenticate(adminSubject);
        given(objectStorage.verifyBytes(asset.getObjectKey(), asset.getObjectVersion(), MAX_BYTES))
                .willThrow(new S3EvidenceObjectStorage.ObjectMarkerChangedException());

        assertThatThrownBy(() -> official.request(fixture.decisionId(), UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(CaseApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
                    assertThat(error.code()).isEqualTo("APPROVAL_BASIS_STALE");
                });
        assertThat(issuances.findByApprovalDecisionId(fixture.decisionId())).isEmpty();
        assertThat(artifacts.findByApprovalDecisionId(fixture.decisionId())).isEmpty();
        assertThat(reports.findById(fixture.reportVersionId()).orElseThrow().getStatus())
                .isEqualTo(ReportVersion.Status.APPROVED.name());
    }

    private Fixture approvedFixture() {
        UUID fanReportId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        FanReport fanReport = new FanReport(fanReportId, "FR-" + fanReportId, tenant, event,
                now.plusDays(120), null, FanReport.ReportType.OTHER, "Synthetic W09 evidence", null,
                null, null, null, now, UUID.randomUUID().toString(), "d".repeat(64), "e".repeat(64));
        fanReport.markReceived();
        fanReports.saveAndFlush(fanReport);
        String objectKey = "fan-reports/" + fanReportId + "/" + evidenceId;
        String sha256 = "a".repeat(64);
        EvidenceAsset asset = new EvidenceAsset(evidenceId, fanReportId, objectKey, "image/png", 4, sha256,
                now, fanReport.getRetentionUntil());
        asset.markStored("etag-" + evidenceId);
        assets.saveAndFlush(asset);
        given(objectStorage.head(objectKey)).willReturn(new EvidenceObjectStorage.ObjectMetadata(4, "image/png", asset.getObjectVersion()));
        given(objectStorage.verifyBytes(objectKey, asset.getObjectVersion(), MAX_BYTES)).willReturn(
                new EvidenceObjectStorage.ByteVerification(4, "image/png", sha256, asset.getObjectVersion(),
                        EvidenceObjectStorage.MarkerKind.ETAG));

        authenticate(reviewerSubject);
        CaseDto.View opened = caseService.create(new CaseDto.Create(event, Case.Type.FAN_REPORT, List.of(),
                List.of(fanReportId), List.of(evidenceId), "Investigate synthetic official PDF evidence", null, null),
                UUID.randomUUID().toString());
        CaseDto.View assigned = caseService.assign(opened.caseId(), new CaseDto.Assign(reviewer, opened.caseVersion(),
                "Assign synthetic reviewer"), UUID.randomUUID().toString());
        CaseDto.View triaged = caseService.transition(opened.caseId(), new CaseDto.Transition(Case.Status.TRIAGED,
                assigned.caseVersion(), "Triage synthetic Case", "Synthetic official PDF classification", null,
                null, null, null, null), UUID.randomUUID().toString());
        CaseDto.View reviewing = caseService.transition(opened.caseId(), new CaseDto.Transition(Case.Status.IN_REVIEW,
                triaged.caseVersion(), "Begin synthetic review", null, null, null, null, null, null), UUID.randomUUID().toString());
        CasePreparationDto.Snapshot snapshot = preparation.capture(opened.caseId(),
                new CasePreparationDto.Capture(reviewing.caseVersion(), "Capture official PDF evidence"), UUID.randomUUID().toString());
        CasePreparationDto.Draft draft = preparation.draft(opened.caseId(), new CasePreparationDto.DraftInput(
                cases.findById(opened.caseId()).orElseThrow().getCaseVersion(), snapshot.evidenceSnapshotId(),
                "합성 승인 보고서", "합성 평가 내용은 공식 PDF 검증을 위해 충분히 길게 작성합니다.",
                "합성 한계 사항 & <추가 확인 필요> é", "합성 결론", "Seal official PDF report"), UUID.randomUUID().toString());
        UUID sourceId = sources.findByCaseIdOrderByLinkedAtAscIdAsc(opened.caseId()).stream()
                .filter(source -> source.getSourceType() == CaseSourceReference.Type.EVIDENCE_ASSET)
                .findFirst().orElseThrow().getId();
        EvidenceIntegrityDto.Verification verification = integrity.verify(opened.caseId(), new EvidenceIntegrityDto.VerifyInput(
                cases.findById(opened.caseId()).orElseThrow().getCaseVersion(), snapshot.evidenceSnapshotId(), sourceId,
                evidenceId, "Verify official PDF evidence bytes"), UUID.randomUUID().toString());
        UUID policyId = UUID.randomUUID();
        policies.saveAndFlush(new EvidencePreservationPolicy(policyId, 1, tenant, event,
                EvidencePreservationPolicy.Status.APPROVED, now.minusDays(1), now.plusDays(90), 90, PROFILE,
                EvidencePreservationPolicy.Mode.GOVERNANCE, "synthetic-authority", now.minusHours(2),
                now.minusHours(1), null));
        given(preservationStorage.verifyLocked(anyString(), nullable(String.class), eq(sha256), eq(4L), eq(MAX_BYTES)))
                .willAnswer(call -> {
                    String requestedVersion = call.getArgument(1, String.class);
                    return new EvidencePreservationStorage.LockedObject(
                            requestedVersion == null ? "locked-" + evidenceId : requestedVersion, sha256, 4,
                            EvidencePreservationPolicy.Mode.GOVERNANCE,
                            now.plusDays(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
                });
        preservation.preserve(opened.caseId(), new EvidencePreservationService.Command(
                cases.findById(opened.caseId()).orElseThrow().getCaseVersion(), snapshot.evidenceSnapshotId(), sourceId,
                evidenceId, verification.recordId(), policyId, "Verify immutable official PDF evidence"), UUID.randomUUID().toString());
        ReportVersion report = reports.findById(draft.reportVersionId()).orElseThrow();
        approvals.submit(opened.caseId(), new ApprovalRequestDto.Submit(draft.reportVersionId(), snapshot.evidenceSnapshotId(),
                cases.findById(opened.caseId()).orElseThrow().getCaseVersion(), report.getVersion(), draft.contentSha256(),
                "Request independently approved official report"), UUID.randomUUID().toString());
        ApprovalRequest request = requests.findByReportVersionId(draft.reportVersionId()).orElseThrow();
        authenticate(approverSubject);
        ApprovalReviewDto.Receipt receipt = review.issueReceipt(request.getId());
        report = reports.findById(draft.reportVersionId()).orElseThrow();
        review.decide(request.getId(), new ApprovalReviewDto.DecisionInput(ApprovalDecision.Outcome.APPROVE,
                "Approve independently reviewed synthetic package", snapshot.evidenceSnapshotId(), snapshot.manifestSha256(),
                draft.contentSha256(), receipt.reviewReceiptToken(), true,
                cases.findById(opened.caseId()).orElseThrow().getCaseVersion(), request.getVersion(), report.getVersion()),
                UUID.randomUUID().toString());
        ApprovalDecision decision = decisions.findByApprovalRequestId(request.getId()).orElseThrow();
        return new Fixture(opened.caseId(), draft.reportVersionId(), evidenceId, decision.getId());
    }

    private List<CaseActivity> holdActivities(UUID caseId) {
        return activities.findAll().stream().filter(activity -> caseId.equals(activity.getCaseId())
                        && activity.getEventType().startsWith("ISSUANCE_HOLD_"))
                .sorted(java.util.Comparator.comparing(CaseActivity::getOccurredAt)).toList();
    }

    private void lease(Duration value) {
        Object target = AopTestUtils.getUltimateTargetObject(writer);
        ReflectionTestUtils.setField(target, "attemptLease", value);
    }

    private void member(UUID id, String subject, AdminRole role) {
        memberships.saveAndFlush(new AdminMembershipEntity(id, tenant, subject, AccessRecordStatus.ACTIVE));
        roles.saveAndFlush(new MembershipRole(UUID.randomUUID(), id, role));
        grants.saveAndFlush(new MembershipEventGrant(UUID.randomUUID(), id, tenant, event));
    }

    private static void authenticate(String subject) {
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(Jwt.withTokenValue(subject)
                .header("alg", "RS256").subject(subject).build());
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Fixture(UUID caseId, UUID reportVersionId, UUID evidenceId, UUID decisionId) { }
    private record Stored(byte[] bytes, OfficialPdfStorage.ObjectProof proof) { }
}
