package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.in.web.dto.CaseDto;
import com.ticket.defender_core.adapter.in.web.dto.CasePreparationDto;
import com.ticket.defender_core.adapter.in.web.dto.EvidenceIntegrityDto;
import com.ticket.defender_core.adapter.out.persistence.*;
import com.ticket.defender_core.application.service.*;
import com.ticket.defender_core.domain.*;
import com.ticket.defender_core.security.AdminRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:evidence-preservation;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false",
        "ticket-defender.evidence-preservation.enabled=true",
        "ticket-defender.evidence-preservation.max-bytes=20971520"
})
class EvidencePreservationIntegrationTest {
    private static final String PROFILE = "W07_TEST_LOCKED";
    private static final long MAX_BYTES = 20L * 1024 * 1024;

    @Autowired private CaseService caseService;
    @Autowired private CasePreparationService preparation;
    @Autowired private EvidenceIntegrityService integrity;
    @Autowired private EvidencePreservationService preservation;
    @Autowired private EvidencePreservationContext preservationContext;
    @Autowired private EvidencePreservationPolicyRepository policies;
    @Autowired private EvidencePreservationRecordRepository preservationRecords;
    @Autowired private EvidencePreservationAttemptRepository attempts;
    @Autowired private EvidenceVerificationRecordRepository verifications;
    @Autowired private CaseRepository cases;
    @Autowired private CaseSourceReferenceRepository sources;
    @Autowired private CaseActivityRepository activities;
    @Autowired private CaseIdempotencyRepository idempotency;
    @Autowired private TenantRepository tenants;
    @Autowired private TenantEventRepository events;
    @Autowired private AdminMembershipRepository memberships;
    @Autowired private MembershipRoleRepository roles;
    @Autowired private MembershipEventGrantRepository grants;
    @Autowired private FanReportRepository reports;
    @Autowired private EvidenceAssetRepository assets;
    @MockitoBean(name = "adminJwtDecoder") private JwtDecoder decoder;
    @MockitoBean private EvidenceObjectStorage objectStorage;
    @MockitoBean private EvidencePreservationStorage preservationStorage;

    private UUID tenant;
    private UUID event;
    private UUID reviewer;
    private String reviewerSubject;

    @BeforeEach
    void setup() {
        reset(objectStorage, preservationStorage, decoder);
        tenant = UUID.randomUUID();
        event = UUID.randomUUID();
        reviewer = UUID.randomUUID();
        reviewerSubject = "w07-reviewer-" + reviewer;
        tenants.save(new Tenant(tenant, "Synthetic W07 tenant", AccessRecordStatus.ACTIVE));
        events.save(new TenantEvent(event, tenant, "w07-" + event, "Synthetic W07 event",
                AccessRecordStatus.ACTIVE));
        member(reviewer, reviewerSubject, AdminRole.REVIEWER, tenant, event);
        member(UUID.randomUUID(), "w07-analyst-" + UUID.randomUUID(), AdminRole.ANALYST, tenant, event);
        given(decoder.decode(anyString())).willAnswer(call -> jwt(call.getArgument(0)));
        given(preservationStorage.profile()).willReturn(PROFILE);
        authenticate(reviewerSubject);
    }

    @Test
    void verifiedLockCreatesAtomicRecordAndSameRequestReplays() {
        Fixture fixture = fixture(null);
        EvidencePreservationPolicy policy = approvedPolicy(fixture, PROFILE, 10,
                EvidencePreservationPolicy.Mode.GOVERNANCE);
        EvidencePreservationService.Command command = command(fixture, policy.getPolicyId());
        String key = UUID.randomUUID().toString();
        long before = cases.findById(fixture.caseId()).orElseThrow().getCaseVersion();
        locked(fixture, "version-1", EvidencePreservationPolicy.Mode.GOVERNANCE,
                LocalDateTime.now(ZoneOffset.UTC).plusDays(5));

        EvidencePreservationService.Result first = preservation.preserve(fixture.caseId(), command, key);
        EvidencePreservationService.Result replay = preservation.preserve(fixture.caseId(), command, key);

        assertThat(replay.recordId()).isEqualTo(first.recordId());
        assertThat(replay.actualVersionId()).isEqualTo(first.actualVersionId());
        assertThat(first.preservationStatus()).isEqualTo(EvidencePreservationRecord.Status.IMMUTABLE_VERIFIED);
        assertThat(first.actualVersionId()).isEqualTo("version-1");
        assertThat(first.objectLockMode()).isEqualTo(EvidencePreservationPolicy.Mode.GOVERNANCE);
        assertThat(preservationRecords.findByCaseIdOrderByVerifiedAtAscIdAsc(fixture.caseId()))
                .singleElement().satisfies(record -> {
                    assertThat(record.getEvidenceSnapshotId()).isEqualTo(fixture.snapshotId());
                    assertThat(record.getEvidenceVerificationRecordId()).isEqualTo(fixture.verificationId());
                    assertThat(record.getPreservationPolicyVersion()).isEqualTo(1);
                    assertThat(record.getTenantId()).isEqualTo(tenant);
                    assertThat(record.getEventId()).isEqualTo(event);
                });
        assertThat(attempts.findByCaseIdOrderByAttemptedAtAscIdAsc(fixture.caseId()))
                .singleElement().extracting(EvidencePreservationAttempt::getOutcome)
                .isEqualTo(EvidencePreservationAttempt.Outcome.VERIFIED);
        assertThat(preservationActivityCount(fixture.caseId())).isEqualTo(1);
        assertThat(cases.findById(fixture.caseId()).orElseThrow().getCaseVersion()).isEqualTo(before + 1);
        assertThat(idempotency.findByTenantIdAndActorMembershipIdAndOperationAndTargetCaseIdAndKeyDigest(
                tenant, reviewer, "EVIDENCE_PRESERVE", fixture.caseId(), CaseSnapshotManifest.sha256(key)))
                .isPresent();
        verify(preservationStorage, times(1)).verifyLocked(preservationKey(fixture), null,
                fixture.sha256(), 4, MAX_BYTES);
    }

    @Test
    void absentDraftRevokedAndExpiredPoliciesAreRejectedAndAudited() {
        Fixture fixture = fixture(null);
        UUID absent = UUID.randomUUID();
        assertCode(() -> preservation.preserve(fixture.caseId(), command(fixture, absent),
                UUID.randomUUID().toString()), HttpStatus.CONFLICT, "PRESERVATION_POLICY_NOT_APPROVED");

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        EvidencePreservationPolicy draft = savePolicy(UUID.randomUUID(), 1, tenant, event,
                EvidencePreservationPolicy.Status.DRAFT, now.minusDays(1), now.plusDays(10),
                5, PROFILE, EvidencePreservationPolicy.Mode.GOVERNANCE, null, null, null);
        EvidencePreservationPolicy revoked = savePolicy(UUID.randomUUID(), 1, tenant, event,
                EvidencePreservationPolicy.Status.REVOKED, now.minusDays(2), now.plusDays(10),
                5, PROFILE, EvidencePreservationPolicy.Mode.GOVERNANCE,
                "synthetic-test-authority", now.minusDays(1), now.minusMinutes(1));
        EvidencePreservationPolicy expired = savePolicy(UUID.randomUUID(), 1, tenant, event,
                EvidencePreservationPolicy.Status.EXPIRED, now.minusDays(10), now.minusMinutes(1),
                5, PROFILE, EvidencePreservationPolicy.Mode.GOVERNANCE,
                "synthetic-test-authority", now.minusDays(9), null);

        for (EvidencePreservationPolicy policy : List.of(draft, revoked, expired)) {
            assertCode(() -> preservation.preserve(fixture.caseId(), command(fixture, policy.getPolicyId()),
                    UUID.randomUUID().toString()), HttpStatus.CONFLICT, "PRESERVATION_POLICY_NOT_APPROVED");
        }

        assertThat(preservationRecords.findByCaseIdOrderByVerifiedAtAscIdAsc(fixture.caseId())).isEmpty();
        assertThat(attempts.findByCaseIdOrderByAttemptedAtAscIdAsc(fixture.caseId()))
                .hasSize(4).allMatch(attempt -> attempt.getOutcome()
                        == EvidencePreservationAttempt.Outcome.POLICY_REJECTED);
        verify(preservationStorage, never()).verifyLocked(anyString(), any(), anyString(), anyLong(), anyLong());
    }

    @Test
    void disabledFeatureCannotBeBypassedByApprovedPolicy() {
        Fixture fixture = fixture(null);
        EvidencePreservationPolicy policy = approvedPolicy(fixture, PROFILE, 5,
                EvidencePreservationPolicy.Mode.GOVERNANCE);
        EvidencePreservationContext.Checked checked = preservationContext.load(fixture.caseId(),
                cases.findById(fixture.caseId()).orElseThrow().getCaseVersion(), fixture.snapshotId(),
                fixture.sourceId(), fixture.evidenceId(), fixture.verificationId(), reviewer);
        EvidencePreservationPolicyGate disabled = new EvidencePreservationPolicyGate(
                policies, preservationStorage, false);

        assertThatThrownBy(() -> disabled.authorize(checked.source(), policy.getPolicyId(),
                LocalDateTime.now(ZoneOffset.UTC))).isInstanceOfSatisfying(
                        EvidencePreservationPolicyGate.Rejection.class,
                        rejection -> assertThat(rejection.code()).isEqualTo("PRESERVATION_DISABLED"));
        assertThat(preservationRecords.findByCaseIdOrderByVerifiedAtAscIdAsc(fixture.caseId())).isEmpty();
    }

    @Test
    void tenantEventAndStorageProfileMismatchesFailClosedBeforeObjectRead() {
        Fixture fixture = fixture(null);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        UUID otherTenant = UUID.randomUUID();
        UUID otherEvent = UUID.randomUUID();
        tenants.save(new Tenant(otherTenant, "Other tenant", AccessRecordStatus.ACTIVE));
        events.save(new TenantEvent(otherEvent, otherTenant, "other-" + otherEvent,
                "Other event", AccessRecordStatus.ACTIVE));
        EvidencePreservationPolicy wrongTenant = savePolicy(UUID.randomUUID(), 1, otherTenant, null,
                EvidencePreservationPolicy.Status.APPROVED, now.minusDays(1), now.plusDays(10), 5,
                PROFILE, EvidencePreservationPolicy.Mode.GOVERNANCE,
                "synthetic-test-authority", now.minusDays(1), null);
        UUID wrongEventId = UUID.randomUUID();
        events.save(new TenantEvent(wrongEventId, tenant, "wrong-" + wrongEventId,
                "Wrong event", AccessRecordStatus.ACTIVE));
        EvidencePreservationPolicy wrongEvent = savePolicy(UUID.randomUUID(), 1, tenant, wrongEventId,
                EvidencePreservationPolicy.Status.APPROVED, now.minusDays(1), now.plusDays(10), 5,
                PROFILE, EvidencePreservationPolicy.Mode.GOVERNANCE,
                "synthetic-test-authority", now.minusDays(1), null);
        EvidencePreservationPolicy wrongProfile = savePolicy(UUID.randomUUID(), 1, tenant, event,
                EvidencePreservationPolicy.Status.APPROVED, now.minusDays(1), now.plusDays(10), 5,
                "UNSUPPORTED", EvidencePreservationPolicy.Mode.GOVERNANCE,
                "synthetic-test-authority", now.minusDays(1), null);

        for (EvidencePreservationPolicy scoped : List.of(wrongTenant, wrongEvent)) {
            assertCode(() -> preservation.preserve(fixture.caseId(), command(fixture, scoped.getPolicyId()),
                    UUID.randomUUID().toString()), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        }
        assertCode(() -> preservation.preserve(fixture.caseId(), command(fixture, wrongProfile.getPolicyId()),
                UUID.randomUUID().toString()), HttpStatus.UNPROCESSABLE_ENTITY,
                "PRESERVATION_STORAGE_UNSUPPORTED");

        assertThat(preservationRecords.findByCaseIdOrderByVerifiedAtAscIdAsc(fixture.caseId())).isEmpty();
        verify(preservationStorage, never()).verifyLocked(anyString(), any(), anyString(), anyLong(), anyLong());
    }

    @Test
    void naturalSourceExpiryRejectsPreservationWithoutObjectRead() throws Exception {
        LocalDateTime deadline = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(2);
        Fixture fixture = fixture(deadline);
        EvidencePreservationPolicy policy = approvedPolicy(fixture, PROFILE, 5,
                EvidencePreservationPolicy.Mode.GOVERNANCE);
        long waitMillis = Math.max(0,
                Duration.between(LocalDateTime.now(ZoneOffset.UTC), deadline).toMillis() + 30);
        Thread.sleep(waitMillis);

        assertCode(() -> preservation.preserve(fixture.caseId(), command(fixture, policy.getPolicyId()),
                UUID.randomUUID().toString()), HttpStatus.UNPROCESSABLE_ENTITY, "RETENTION_EXPIRED");
        assertThat(preservationRecords.findByCaseIdOrderByVerifiedAtAscIdAsc(fixture.caseId())).isEmpty();
        verify(preservationStorage, never()).verifyLocked(anyString(), any(), anyString(), anyLong(), anyLong());
    }

    @Test
    void retentionLimitAndLockProofFailuresNeverCreateSuccessRecord() {
        Fixture fixture = fixture(null);
        EvidencePreservationPolicy policy = approvedPolicy(fixture, PROFILE, 2,
                EvidencePreservationPolicy.Mode.GOVERNANCE);
        EvidencePreservationService.Command command = command(fixture, policy.getPolicyId());
        when(preservationStorage.verifyLocked(anyString(), isNull(), eq(fixture.sha256()), eq(4L), eq(MAX_BYTES)))
                .thenReturn(new EvidencePreservationStorage.LockedObject("too-long", fixture.sha256(), 4,
                        EvidencePreservationPolicy.Mode.GOVERNANCE,
                        LocalDateTime.now(ZoneOffset.UTC).plusDays(3)));
        assertCode(() -> preservation.preserve(fixture.caseId(), command, UUID.randomUUID().toString()),
                HttpStatus.UNPROCESSABLE_ENTITY, "PRESERVATION_RETENTION_EXCEEDED");

        reset(preservationStorage);
        given(preservationStorage.profile()).willReturn(PROFILE);
        when(preservationStorage.verifyLocked(anyString(), isNull(), eq(fixture.sha256()), eq(4L), eq(MAX_BYTES)))
                .thenThrow(new EvidencePreservationStorage.ObjectLockNotVerifiedException());
        assertCode(() -> preservation.preserve(fixture.caseId(), command, UUID.randomUUID().toString()),
                HttpStatus.UNPROCESSABLE_ENTITY, "OBJECT_LOCK_NOT_VERIFIED");

        reset(preservationStorage);
        given(preservationStorage.profile()).willReturn(PROFILE);
        when(preservationStorage.verifyLocked(anyString(), isNull(), eq(fixture.sha256()), eq(4L), eq(MAX_BYTES)))
                .thenThrow(new EvidencePreservationStorage.ObjectLockUnsupportedException());
        assertCode(() -> preservation.preserve(fixture.caseId(), command, UUID.randomUUID().toString()),
                HttpStatus.UNPROCESSABLE_ENTITY, "PRESERVATION_STORAGE_UNSUPPORTED");

        assertThat(preservationRecords.findByCaseIdOrderByVerifiedAtAscIdAsc(fixture.caseId())).isEmpty();
        assertThat(attempts.findByCaseIdOrderByAttemptedAtAscIdAsc(fixture.caseId()))
                .hasSize(3).allMatch(attempt -> attempt.getOutcome()
                        == EvidencePreservationAttempt.Outcome.STORAGE_REJECTED);
    }

    @Test
    void storageOutageIsAuditedWithoutIdempotentFailureAndSameKeyCanRetry() {
        Fixture fixture = fixture(null);
        EvidencePreservationPolicy policy = approvedPolicy(fixture, PROFILE, 10,
                EvidencePreservationPolicy.Mode.GOVERNANCE);
        EvidencePreservationService.Command command = command(fixture, policy.getPolicyId());
        String key = UUID.randomUUID().toString();
        long before = cases.findById(fixture.caseId()).orElseThrow().getCaseVersion();
        when(preservationStorage.verifyLocked(anyString(), isNull(), eq(fixture.sha256()), eq(4L), eq(MAX_BYTES)))
                .thenThrow(new EvidencePreservationStorage.StorageUnavailableException("synthetic outage"));

        assertCode(() -> preservation.preserve(fixture.caseId(), command, key),
                HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE");
        assertThat(cases.findById(fixture.caseId()).orElseThrow().getCaseVersion()).isEqualTo(before);
        assertThat(preservationRecords.findByCaseIdOrderByVerifiedAtAscIdAsc(fixture.caseId())).isEmpty();
        assertThat(idempotency.findByTenantIdAndActorMembershipIdAndOperationAndTargetCaseIdAndKeyDigest(
                tenant, reviewer, "EVIDENCE_PRESERVE", fixture.caseId(), CaseSnapshotManifest.sha256(key)))
                .isEmpty();

        locked(fixture, "recovered-version", EvidencePreservationPolicy.Mode.GOVERNANCE,
                LocalDateTime.now(ZoneOffset.UTC).plusDays(5));
        EvidencePreservationService.Result recovered = preservation.preserve(fixture.caseId(), command, key);

        assertThat(recovered.actualVersionId()).isEqualTo("recovered-version");
        assertThat(attempts.findByCaseIdOrderByAttemptedAtAscIdAsc(fixture.caseId()))
                .extracting(EvidencePreservationAttempt::getOutcome)
                .containsExactly(EvidencePreservationAttempt.Outcome.STORAGE_UNAVAILABLE,
                        EvidencePreservationAttempt.Outcome.VERIFIED);
        verify(preservationStorage, times(2)).verifyLocked(preservationKey(fixture), null,
                fixture.sha256(), 4, MAX_BYTES);
    }

    @Test
    void caseAndPolicyChangesDuringObjectReadAreRecheckedBeforeCommit() {
        Fixture caseRace = fixture(null);
        EvidencePreservationPolicy casePolicy = approvedPolicy(caseRace, PROFILE, 10,
                EvidencePreservationPolicy.Mode.GOVERNANCE);
        when(preservationStorage.verifyLocked(eq(preservationKey(caseRace)), isNull(),
                eq(caseRace.sha256()), eq(4L), eq(MAX_BYTES))).thenAnswer(invocation -> {
            Case changed = cases.findById(caseRace.caseId()).orElseThrow();
            changed.recordPreparation(LocalDateTime.now(ZoneOffset.UTC));
            cases.saveAndFlush(changed);
            return lockedObject(caseRace, "case-race", EvidencePreservationPolicy.Mode.GOVERNANCE, 5);
        });
        assertCode(() -> preservation.preserve(caseRace.caseId(), command(caseRace, casePolicy.getPolicyId()),
                UUID.randomUUID().toString()), HttpStatus.CONFLICT, "CASE_VERSION_CONFLICT");
        assertThat(preservationRecords.findByCaseIdOrderByVerifiedAtAscIdAsc(caseRace.caseId())).isEmpty();

        Fixture policyRace = fixture(null);
        EvidencePreservationPolicy policy = approvedPolicy(policyRace, PROFILE, 10,
                EvidencePreservationPolicy.Mode.GOVERNANCE);
        when(preservationStorage.verifyLocked(eq(preservationKey(policyRace)), isNull(),
                eq(policyRace.sha256()), eq(4L), eq(MAX_BYTES))).thenAnswer(invocation -> {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            savePolicy(policy.getPolicyId(), 2, tenant, event, EvidencePreservationPolicy.Status.REVOKED,
                    now.minusDays(1), now.plusDays(10), 10, PROFILE,
                    EvidencePreservationPolicy.Mode.GOVERNANCE, "synthetic-test-authority",
                    now.minusDays(1), now);
            return lockedObject(policyRace, "policy-race", EvidencePreservationPolicy.Mode.GOVERNANCE, 5);
        });
        assertCode(() -> preservation.preserve(policyRace.caseId(), command(policyRace, policy.getPolicyId()),
                UUID.randomUUID().toString()), HttpStatus.CONFLICT, "PRESERVATION_POLICY_NOT_APPROVED");
        assertThat(preservationRecords.findByCaseIdOrderByVerifiedAtAscIdAsc(policyRace.caseId())).isEmpty();
    }

    @Test
    void unauthorizedRoleAndMismatchedVerificationCannotReachPreservationStorage() {
        Fixture fixture = fixture(null);
        EvidencePreservationPolicy policy = approvedPolicy(fixture, PROFILE, 10,
                EvidencePreservationPolicy.Mode.GOVERNANCE);
        String analyst = memberships.findAll().stream()
                .map(AdminMembershipEntity::getSubject)
                .filter(subject -> subject.startsWith("w07-analyst-"))
                .findFirst().orElseThrow();
        authenticate(analyst);
        assertThatThrownBy(() -> preservation.preserve(fixture.caseId(), command(fixture, policy.getPolicyId()),
                UUID.randomUUID().toString())).isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
        authenticate(reviewerSubject);
        EvidencePreservationService.Command wrongVerification = new EvidencePreservationService.Command(
                cases.findById(fixture.caseId()).orElseThrow().getCaseVersion(), fixture.snapshotId(),
                fixture.sourceId(), fixture.evidenceId(), UUID.randomUUID(), policy.getPolicyId(),
                "Verify synthetic preservation");
        assertCode(() -> preservation.preserve(fixture.caseId(), wrongVerification,
                UUID.randomUUID().toString()), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertThat(preservationRecords.findByCaseIdOrderByVerifiedAtAscIdAsc(fixture.caseId())).isEmpty();
        verify(preservationStorage, never()).verifyLocked(anyString(), any(), anyString(), anyLong(), anyLong());
    }

    private Fixture fixture(LocalDateTime retentionUntil) {
        UUID reportId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        FanReport report = new FanReport(reportId, "FR-" + reportId, tenant, event,
                now.plusDays(1), null, FanReport.ReportType.OTHER, "Synthetic W07 report",
                null, null, null, null, now.minusDays(1), UUID.randomUUID().toString(),
                "d".repeat(64), "e".repeat(64));
        if (retentionUntil != null) report.setRetentionUntil(retentionUntil);
        report.markReceived();
        reports.saveAndFlush(report);
        UUID evidenceId = UUID.randomUUID();
        String sourceKey = "fan-reports/" + reportId + "/" + evidenceId;
        String sha256 = sha256(new byte[]{1, 2, 3, 4});
        EvidenceAsset asset = new EvidenceAsset(evidenceId, reportId, sourceKey, "image/png", 4,
                sha256, now, report.getRetentionUntil());
        asset.markStored("etag");
        assets.saveAndFlush(asset);
        when(objectStorage.head(sourceKey)).thenReturn(
                new EvidenceObjectStorage.ObjectMetadata(4, "image/png", "etag"));
        when(objectStorage.verifyBytes(sourceKey, "etag", MAX_BYTES)).thenReturn(
                new EvidenceObjectStorage.ByteVerification(4, "image/png", sha256, "etag",
                        EvidenceObjectStorage.MarkerKind.ETAG));
        CaseDto.View opened = caseService.create(new CaseDto.Create(event, Case.Type.FAN_REPORT,
                List.of(), List.of(reportId), List.of(evidenceId), "Investigate synthetic report", null, null),
                UUID.randomUUID().toString());
        CaseDto.View assigned = caseService.assign(opened.caseId(),
                new CaseDto.Assign(reviewer, opened.caseVersion(), "Assign reviewer"),
                UUID.randomUUID().toString());
        CaseDto.View triaged = caseService.transition(opened.caseId(),
                new CaseDto.Transition(Case.Status.TRIAGED, assigned.caseVersion(), "Triage case",
                        "Synthetic classification", null, null, null, null, null),
                UUID.randomUUID().toString());
        CaseDto.View reviewing = caseService.transition(opened.caseId(),
                new CaseDto.Transition(Case.Status.IN_REVIEW, triaged.caseVersion(), "Begin review",
                        null, null, null, null, null, null), UUID.randomUUID().toString());
        var snapshot = preparation.capture(opened.caseId(),
                new CasePreparationDto.Capture(reviewing.caseVersion(), "Capture synthetic evidence"),
                UUID.randomUUID().toString());
        UUID sourceId = sources.findByCaseIdOrderByLinkedAtAscIdAsc(opened.caseId()).stream()
                .filter(source -> source.getSourceType() == CaseSourceReference.Type.EVIDENCE_ASSET)
                .findFirst().orElseThrow().getId();
        EvidenceIntegrityDto.Verification verification = integrity.verify(opened.caseId(),
                new EvidenceIntegrityDto.VerifyInput(
                        cases.findById(opened.caseId()).orElseThrow().getCaseVersion(),
                        snapshot.evidenceSnapshotId(), sourceId, evidenceId, "Verify synthetic bytes"),
                UUID.randomUUID().toString());
        assertThat(verification.verificationStatus()).isEqualTo(EvidenceVerificationRecord.Status.MATCH);
        return new Fixture(opened.caseId(), snapshot.evidenceSnapshotId(), sourceId, evidenceId,
                verification.recordId(), sha256);
    }

    private EvidencePreservationPolicy approvedPolicy(Fixture fixture, String profile, int limitDays,
            EvidencePreservationPolicy.Mode mode) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return savePolicy(UUID.randomUUID(), 1, tenant, event, EvidencePreservationPolicy.Status.APPROVED,
                now.minusDays(1), now.plusDays(30), limitDays, profile, mode,
                "synthetic-test-authority", now.minusHours(1), null);
    }

    private EvidencePreservationPolicy savePolicy(UUID policyId, long version, UUID policyTenant,
            UUID policyEvent, EvidencePreservationPolicy.Status status, LocalDateTime from,
            LocalDateTime until, int limitDays, String profile, EvidencePreservationPolicy.Mode mode,
            String authority, LocalDateTime approvedAt, LocalDateTime revokedAt) {
        return policies.saveAndFlush(new EvidencePreservationPolicy(policyId, version, policyTenant,
                policyEvent, status, from, until, limitDays, profile, mode, authority,
                LocalDateTime.now(ZoneOffset.UTC), approvedAt, revokedAt));
    }

    private EvidencePreservationService.Command command(Fixture fixture, UUID policyId) {
        return new EvidencePreservationService.Command(
                cases.findById(fixture.caseId()).orElseThrow().getCaseVersion(), fixture.snapshotId(),
                fixture.sourceId(), fixture.evidenceId(), fixture.verificationId(), policyId,
                "Verify synthetic preservation");
    }

    private void locked(Fixture fixture, String version, EvidencePreservationPolicy.Mode mode,
            LocalDateTime retainUntil) {
        doReturn(new EvidencePreservationStorage.LockedObject(version, fixture.sha256(), 4, mode, retainUntil))
                .when(preservationStorage).verifyLocked(
                        preservationKey(fixture), null, fixture.sha256(), 4, MAX_BYTES);
    }

    private static EvidencePreservationStorage.LockedObject lockedObject(Fixture fixture, String version,
            EvidencePreservationPolicy.Mode mode, int days) {
        return new EvidencePreservationStorage.LockedObject(version, fixture.sha256(), 4, mode,
                LocalDateTime.now(ZoneOffset.UTC).plusDays(days));
    }

    private String preservationKey(Fixture fixture) {
        return "preserved-evidence/" + tenant + "/" + event + "/" + fixture.caseId()
                + "/" + fixture.evidenceId();
    }

    private long preservationActivityCount(UUID caseId) {
        return activities.findByCaseId(caseId, org.springframework.data.domain.Pageable.unpaged()).stream()
                .filter(activity -> activity.getEventType().equals("EVIDENCE_PRESERVATION_VERIFIED"))
                .count();
    }

    private void member(UUID id, String subject, AdminRole role, UUID memberTenant, UUID memberEvent) {
        memberships.save(new AdminMembershipEntity(id, memberTenant, subject, AccessRecordStatus.ACTIVE));
        roles.save(new MembershipRole(UUID.randomUUID(), id, role));
        grants.save(new MembershipEventGrant(UUID.randomUUID(), id, memberTenant, memberEvent));
    }

    private static void authenticate(String subject) {
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt(subject));
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private static Jwt jwt(String subject) {
        return Jwt.withTokenValue(subject).header("alg", "RS256").subject(subject).build();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void assertCode(Runnable action, HttpStatus status, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(CaseApiException.class, error -> {
            assertThat(error.status()).isEqualTo(status);
            assertThat(error.code()).isEqualTo(code);
        });
    }

    private record Fixture(UUID caseId, UUID snapshotId, UUID sourceId, UUID evidenceId,
                           UUID verificationId, String sha256) { }
}
