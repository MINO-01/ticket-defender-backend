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

import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.jdbc.core.JdbcTemplate;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:official-pdf;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false",
        "ticket-defender.evidence-preservation.enabled=true",
        "ticket-defender.evidence-preservation.max-bytes=20971520",
        "ticket-defender.official-pdf.enabled=true",
        "ticket-defender.official-pdf.operational-mode=SYNTHETIC_ISOLATED",
        "ticket-defender.official-pdf.synthetic-isolation-id=" + OfficialPdfIssuanceIntegrationTest.ISOLATION
})
@AutoConfigureMockMvc
class OfficialPdfIssuanceIntegrationTest {
    static final String ISOLATION = "6f7d2f4e-3c1a-4b8e-9d55-0a6c1e2b7f31";
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
    @Autowired private OfficialPdfSyntheticTenantRepository syntheticTenants;
    @MockitoBean private EvidenceObjectStorage objectStorage;
    @MockitoBean private EvidencePreservationStorage preservationStorage;
    @MockitoBean private OfficialPdfStorage officialStorage;
    @MockitoSpyBean private OfficialPdfRenderer renderer;
    @MockitoSpyBean private OfficialPdfIssuanceWriteService writer;
    @MockitoSpyBean private CaseActivityRepository activities;
    @Autowired private InternalIssuanceHoldWriteService holdWriter;
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean(name = "adminJwtDecoder") private JwtDecoder adminDecoder;

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
        reset(objectStorage, preservationStorage, officialStorage, renderer, writer, activities, adminDecoder);
        given(adminDecoder.decode(anyString())).willAnswer(call -> Jwt.withTokenValue(call.getArgument(0))
                .header("alg", "RS256").subject(call.getArgument(0)).build());
        lease(Duration.ofMinutes(5));
        stored.clear();
        tenant = UUID.randomUUID();
        event = UUID.randomUUID();
        reviewer = UUID.randomUUID();
        reviewerSubject = "w09-reviewer-" + reviewer;
        approverSubject = "w09-approver-" + UUID.randomUUID();
        adminSubject = "w09-admin-" + UUID.randomUUID();
        tenants.saveAndFlush(new Tenant(tenant, "Synthetic W09 tenant", AccessRecordStatus.ACTIVE));
        // 합성 고객사 등록은 API가 아니라 서버 소유 fixture로만 만들며, Case와 증거보다 먼저 등록합니다.
        syntheticTenants.saveAndFlush(new OfficialPdfSyntheticTenant(tenant, UUID.fromString(ISOLATION),
                LocalDateTime.now(ZoneOffset.UTC).minusSeconds(5)));
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

    @Test
    void multiTenantAdminIsBlockedOnEveryOfficialPdfApiWithoutNewIssuanceOrDownload() throws Exception {
        Fixture fixture = approvedFixture();
        authenticate(adminSubject);
        OfficialPdfDto.Issuance issued = official.request(fixture.decisionId(), UUID.randomUUID().toString());
        SecurityContextHolder.clearContext();
        long issuanceCount = issuances.count();
        long artifactCount = artifacts.count();
        long attemptCount = attempts.count();
        int auditCount = audits.findByIssuanceIdOrderByOccurredAtAscIdAsc(issued.officialPdfIssuanceId()).size();
        clearInvocations(officialStorage, renderer);
        // 발급 권한이 있는 TENANT_ADMIN이 두 번째 활성 고객사에도 활성 멤버십을 가지게 됩니다.
        UUID otherTenant = UUID.randomUUID();
        tenants.saveAndFlush(new Tenant(otherTenant, "Extra synthetic W09 tenant", AccessRecordStatus.ACTIVE));
        UUID extra = UUID.randomUUID();
        memberships.saveAndFlush(new AdminMembershipEntity(extra, otherTenant, adminSubject, AccessRecordStatus.ACTIVE));
        roles.saveAndFlush(new MembershipRole(UUID.randomUUID(), extra, AdminRole.TENANT_ADMIN));

        String issuanceId = issued.officialPdfIssuanceId().toString();
        for (var call : List.of(
                get("/api/v1/approval-decisions/{id}/official-pdf-eligibility", fixture.decisionId()),
                post("/api/v1/approval-decisions/{id}/official-pdf-issuances", fixture.decisionId())
                        .header("Idempotency-Key", UUID.randomUUID().toString()),
                get("/api/v1/official-pdf-issuances/{id}", issuanceId),
                post("/api/v1/official-pdf-issuances/{id}/retry", issuanceId)
                        .header("Idempotency-Key", UUID.randomUUID().toString()),
                get("/api/v1/report-versions/{id}/official-pdf-issuances", fixture.reportVersionId()),
                get("/api/v1/official-pdf-issuances/{id}/download", issuanceId))) {
            mvc.perform(call.header("Authorization", "Bearer " + adminSubject))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TENANT_SELECTION_REQUIRED"))
                    .andExpect(header().string("Content-Type", org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("application/pdf"))))
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString(tenant.toString()))))
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString(event.toString()))));
        }

        // 차단된 요청은 발급 기록, 산출물, 시도, 감사 기록을 만들지 않고 저장소의 PDF를 읽지 않습니다.
        assertThat(issuances.count()).isEqualTo(issuanceCount);
        assertThat(artifacts.count()).isEqualTo(artifactCount);
        assertThat(attempts.count()).isEqualTo(attemptCount);
        assertThat(audits.findByIssuanceIdOrderByOccurredAtAscIdAsc(issued.officialPdfIssuanceId()))
                .hasSize(auditCount);
        verify(officialStorage, never()).putIfAbsent(any(UUID.class), anyString(), any(byte[].class));
        verify(officialStorage, never()).download(any(UUID.class), anyString(), anyString(), anyString(), anyLong());
        verify(renderer, never()).render(any());
        assertThat(decisions.findById(fixture.decisionId())).isPresent();

        // 추가 멤버십이 비활성화되면 기존 권한 검사에 따라 다운로드할 수 있습니다.
        jdbc.update("update memberships set status = 'INACTIVE' where id = ?", extra.toString());
        mvc.perform(get("/api/v1/official-pdf-issuances/{id}/download", issuanceId)
                        .header("Authorization", "Bearer " + adminSubject))
                .andExpect(status().isOk());
    }

    @Test
    void unregisteredSyntheticTenantBlocksEveryOfficialPdfApiWithoutTouchingExistingHistory() throws Exception {
        Fixture fixture = approvedFixture();
        authenticate(adminSubject);
        OfficialPdfDto.Issuance issued = official.request(fixture.decisionId(), UUID.randomUUID().toString());
        assertThat(issued.status()).isEqualTo(OfficialPdfIssuance.Status.GENERATED);
        SecurityContextHolder.clearContext();
        long issuanceCount = issuances.count();
        long artifactCount = artifacts.count();
        long attemptCount = attempts.count();
        int auditCount = audits.findByIssuanceIdOrderByOccurredAtAscIdAsc(issued.officialPdfIssuanceId()).size();
        String pdfSha256 = artifacts.findByIssuanceId(issued.officialPdfIssuanceId()).orElseThrow().getPdfSha256();
        clearInvocations(officialStorage, renderer);
        // 서버 소유 등록이 사라지면 환경 설정이 그대로여도 합성 범위를 확인할 수 없습니다.
        syntheticTenants.deleteById(tenant);

        String issuanceId = issued.officialPdfIssuanceId().toString();
        mvc.perform(get("/api/v1/approval-decisions/{id}/official-pdf-eligibility", fixture.decisionId())
                        .header("Authorization", "Bearer " + adminSubject))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("BLOCKED"))
                .andExpect(jsonPath("$.code").value("OFFICIAL_PDF_SYNTHETIC_SCOPE_UNVERIFIED"));
        for (var call : List.of(
                post("/api/v1/approval-decisions/{id}/official-pdf-issuances", fixture.decisionId())
                        .header("Idempotency-Key", UUID.randomUUID().toString()),
                get("/api/v1/official-pdf-issuances/{id}", issuanceId),
                post("/api/v1/official-pdf-issuances/{id}/retry", issuanceId)
                        .header("Idempotency-Key", UUID.randomUUID().toString()),
                get("/api/v1/report-versions/{id}/official-pdf-issuances", fixture.reportVersionId()),
                get("/api/v1/official-pdf-issuances/{id}/download", issuanceId))) {
            mvc.perform(call.header("Authorization", "Bearer " + adminSubject))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("OFFICIAL_PDF_SYNTHETIC_SCOPE_UNVERIFIED"))
                    .andExpect(header().string("Content-Type", org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("application/pdf"))))
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString(pdfSha256))));
        }
        // 인증 없는 호출은 합성 범위 판정보다 먼저 차단됩니다.
        mvc.perform(get("/api/v1/official-pdf-issuances/{id}/download", issuanceId))
                .andExpect(status().isUnauthorized());

        // 차단은 기존 발급 이력, 산출물, 감사 기록을 지우거나 새로 만들지 않습니다.
        assertThat(issuances.count()).isEqualTo(issuanceCount);
        assertThat(artifacts.count()).isEqualTo(artifactCount);
        assertThat(attempts.count()).isEqualTo(attemptCount);
        assertThat(audits.findByIssuanceIdOrderByOccurredAtAscIdAsc(issued.officialPdfIssuanceId()))
                .hasSize(auditCount);
        assertThat(issuances.findById(issued.officialPdfIssuanceId())).hasValueSatisfying(
                issuance -> assertThat(issuance.getStatus()).isEqualTo(OfficialPdfIssuance.Status.GENERATED));
        verify(renderer, never()).render(any());
        verify(officialStorage, never()).putIfAbsent(any(UUID.class), anyString(), any(byte[].class));
        verify(officialStorage, never()).download(any(UUID.class), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void registrationRevokedAfterReservationStopsRenderingAndRetryCannotBypassTheGate() {
        Fixture fixture = approvedFixture();
        authenticate(adminSubject);
        doAnswer(call -> {
            Object reservation = call.callRealMethod();
            syntheticTenants.deleteById(tenant);
            return reservation;
        }).when(writer).reserveForDecision(any(), any(), anyString(), anyString(), anyBoolean());

        OfficialPdfDto.Issuance failed = official.request(fixture.decisionId(), UUID.randomUUID().toString());

        assertThat(failed.status()).isEqualTo(OfficialPdfIssuance.Status.FAILED);
        assertThat(failed.lastFailureCode()).isEqualTo("OFFICIAL_PDF_SYNTHETIC_SCOPE_UNVERIFIED");
        assertThat(artifacts.findByIssuanceId(failed.officialPdfIssuanceId())).isEmpty();
        verify(renderer, never()).render(any());
        verify(officialStorage, never()).putIfAbsent(any(UUID.class), anyString(), any(byte[].class));
        assertThat(audits.findByIssuanceIdOrderByOccurredAtAscIdAsc(failed.officialPdfIssuanceId()))
                .extracting(OfficialPdfAuditEvent::getEventType, OfficialPdfAuditEvent::getDetailCode)
                .contains(tuple("ISSUANCE_FAILED", "OFFICIAL_PDF_SYNTHETIC_SCOPE_UNVERIFIED"));

        long attemptCount = attempts.count();
        assertThatThrownBy(() -> official.retry(failed.officialPdfIssuanceId(), UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("OFFICIAL_PDF_SYNTHETIC_SCOPE_UNVERIFIED"));
        assertThat(attempts.count()).isEqualTo(attemptCount);
        assertThat(artifacts.findByIssuanceId(failed.officialPdfIssuanceId())).isEmpty();
    }

    @Test
    void registrationRevokedAfterObjectStorageBlocksFinalizationWithoutArtifact() {
        Fixture fixture = approvedFixture();
        authenticate(adminSubject);
        doAnswer(call -> {
            call.callRealMethod();
            syntheticTenants.deleteById(tenant);
            return null;
        }).when(writer).recordObject(any(), any(), any(), any());

        OfficialPdfDto.Issuance failed = official.request(fixture.decisionId(), UUID.randomUUID().toString());

        assertThat(failed.status()).isEqualTo(OfficialPdfIssuance.Status.FAILED);
        assertThat(failed.lastFailureCode()).isEqualTo("OFFICIAL_PDF_SYNTHETIC_SCOPE_UNVERIFIED");
        assertThat(artifacts.findByIssuanceId(failed.officialPdfIssuanceId())).isEmpty();
        assertThat(audits.findByIssuanceIdOrderByOccurredAtAscIdAsc(failed.officialPdfIssuanceId()))
                .extracting(OfficialPdfAuditEvent::getEventType).doesNotContain("ISSUANCE_GENERATED");
    }

    @Test
    void retroactiveRegistrationOrDifferentIsolationIdentityBlocksIssuanceBeforeAnyRecord() {
        Fixture fixture = approvedFixture();
        authenticate(adminSubject);
        long issuanceCount = issuances.count();
        // 이미 존재하던 Case를 나중에 합성 고객사로 등록한 경우입니다.
        jdbc.update("UPDATE official_pdf_synthetic_tenants SET registered_at = ? WHERE tenant_id = ?",
                LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1), tenant.toString());
        assertThatThrownBy(() -> official.request(fixture.decisionId(), UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("OFFICIAL_PDF_SYNTHETIC_SCOPE_UNVERIFIED"));
        // 다른 격리 환경에서 만든 등록 행은 이 서버의 격리 식별자와 대조되지 않습니다.
        jdbc.update("UPDATE official_pdf_synthetic_tenants SET registered_at = ?, isolation_id = ? WHERE tenant_id = ?",
                LocalDateTime.now(ZoneOffset.UTC).minusDays(1), UUID.randomUUID().toString(), tenant.toString());
        assertThatThrownBy(() -> official.request(fixture.decisionId(), UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("OFFICIAL_PDF_SYNTHETIC_SCOPE_UNVERIFIED"));
        assertThat(issuances.count()).isEqualTo(issuanceCount);
        verify(renderer, never()).render(any());
        verify(officialStorage, never()).putIfAbsent(any(UUID.class), anyString(), any(byte[].class));
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
