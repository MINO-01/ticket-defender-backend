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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.services.s3.model.S3Exception;
import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:evidence-integrity;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false"
})
@AutoConfigureMockMvc
@Transactional
class EvidenceIntegrityIntegrationTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID EVENT = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID REVIEWER = UUID.fromString("66666666-6666-4666-8666-666666666666");

    @Autowired private CaseService caseService;
    @Autowired private CasePreparationService preparation;
    @Autowired private EvidenceIntegrityService integrity;
    @Autowired private CaseRepository cases;
    @Autowired private CaseSourceReferenceRepository sources;
    @Autowired private CaseActivityRepository activities;
    @Autowired private EvidenceVerificationRecordRepository records;
    @Autowired private CaseIdempotencyRepository idempotency;
    @Autowired private TenantRepository tenants;
    @Autowired private TenantEventRepository events;
    @Autowired private AdminMembershipRepository memberships;
    @Autowired private MembershipRoleRepository roles;
    @Autowired private MembershipEventGrantRepository grants;
    @Autowired private FanReportRepository reports;
    @Autowired private EvidenceAssetRepository assets;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private javax.sql.DataSource dataSource;
    @MockitoBean(name = "adminJwtDecoder") private JwtDecoder decoder;
    @MockitoBean private EvidenceObjectStorage storage;

    @BeforeEach
    void setup() {
        given(decoder.decode(anyString())).willAnswer(call -> jwt(call.getArgument(0)));
        tenants.save(new Tenant(TENANT, "Synthetic tenant", AccessRecordStatus.ACTIVE));
        events.save(new TenantEvent(EVENT, TENANT, "synthetic-show", "Synthetic show", AccessRecordStatus.ACTIVE));
        member(REVIEWER, "reviewer", AdminRole.REVIEWER);
        member(UUID.randomUUID(), "analyst", AdminRole.ANALYST);
        member(UUID.randomUUID(), "approver", AdminRole.APPROVER);
        as("reviewer");
    }

    @Test
    void matchingBytesAreRecordedWithoutClaimingApprovalAndSameKeyReplays() {
        Fixture fixture = fixture();
        when(storage.verifyBytes(fixture.key(), "etag", 20L * 1024 * 1024))
                .thenReturn(bytes(fixture.originalSha256()));
        EvidenceIntegrityDto.VerifyInput input = input(fixture);
        String key = UUID.randomUUID().toString();

        EvidenceIntegrityDto.Verification first = integrity.verify(fixture.caseId(), input, key);
        assertThat(first.verificationStatus()).isEqualTo(EvidenceVerificationRecord.Status.MATCH);
        assertThat(first.preservationStatus()).isEqualTo(EvidenceVerificationRecord.Preservation.UNPROTECTED);
        assertThat(integrity.verify(fixture.caseId(), input, key)).isEqualTo(first);
        assertThat(records.findByCaseId(fixture.caseId(), org.springframework.data.domain.Pageable.unpaged()))
                .hasSize(1);
        assertThatThrownBy(() -> integrity.verify(fixture.caseId(), new EvidenceIntegrityDto.VerifyInput(
                input.expectedCaseVersion(), input.evidenceSnapshotId(), input.sourceReferenceId(),
                input.evidenceId(), "Different synthetic reason"), key))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        assertThat(activities.findByCaseId(fixture.caseId(), org.springframework.data.domain.Pageable.unpaged())
                .stream().filter(activity -> activity.getEventType().equals("EVIDENCE_BYTES_CHECKED"))).hasSize(1);
        verify(storage, times(1)).verifyBytes(fixture.key(), "etag", 20L * 1024 * 1024);

        CasePreparationDto.Draft draft = preparation.draft(fixture.caseId(), new CasePreparationDto.DraftInput(
                cases.findById(fixture.caseId()).orElseThrow().getCaseVersion(), fixture.snapshotId(),
                "Synthetic title", "Synthetic assessment for reviewer", "Original not immutable",
                "Further review needed", "Prepare draft"), UUID.randomUUID().toString());
        var evaluation = integrity.evaluate(fixture.caseId(), draft.reportVersionId());
        assertThat(evaluation.technicalIntegrity()).isEqualTo("VERIFIED_AT_TIME");
        assertThat(evaluation.approvalEligibility()).isEqualTo("BLOCKED");
        assertThat(evaluation.reasons()).contains("POLICY_UNAPPROVED", "ORIGINAL_NOT_IMMUTABLE");
        assertThat(draft.renderable()).isFalse();
    }

    @Test
    void reportWithoutFileUsesMetadataAssessmentAndStaysApprovalBlocked() {
        UUID reportId = UUID.randomUUID();
        FanReport report = new FanReport(reportId, "FR-" + reportId, TENANT, EVENT,
                LocalDateTime.now(ZoneOffset.UTC).plusDays(1), null, FanReport.ReportType.OTHER,
                "Synthetic report without file", null, null, null, null,
                LocalDateTime.now(ZoneOffset.UTC), UUID.randomUUID().toString(),
                "d".repeat(64), "e".repeat(64));
        report.markReceived();
        reports.saveAndFlush(report);
        CaseDto.View opened = caseService.create(new CaseDto.Create(EVENT, Case.Type.FAN_REPORT,
                List.of(), List.of(reportId), List.of(), "Investigate synthetic report", null, null),
                UUID.randomUUID().toString());
        CaseDto.View assigned = caseService.assign(opened.caseId(),
                new CaseDto.Assign(REVIEWER, opened.caseVersion(), "Assign reviewer"), UUID.randomUUID().toString());
        CaseDto.View triaged = caseService.transition(opened.caseId(),
                new CaseDto.Transition(Case.Status.TRIAGED, assigned.caseVersion(), "Triage case",
                        "Synthetic classification", null, null, null, null, null), UUID.randomUUID().toString());
        CaseDto.View reviewing = caseService.transition(opened.caseId(),
                new CaseDto.Transition(Case.Status.IN_REVIEW, triaged.caseVersion(), "Begin review",
                        null, null, null, null, null, null), UUID.randomUUID().toString());
        var snapshot = preparation.capture(opened.caseId(),
                new CasePreparationDto.Capture(reviewing.caseVersion(), "Capture report metadata"),
                UUID.randomUUID().toString());
        var draft = preparation.draft(opened.caseId(), new CasePreparationDto.DraftInput(
                cases.findById(opened.caseId()).orElseThrow().getCaseVersion(), snapshot.evidenceSnapshotId(),
                "Synthetic title", "Synthetic assessment for reviewer", "No file was submitted",
                "Further review needed", "Prepare draft"), UUID.randomUUID().toString());

        var evaluation = integrity.evaluate(opened.caseId(), draft.reportVersionId());
        assertThat(evaluation.technicalIntegrity()).isEqualTo("METADATA_VALID");
        assertThat(evaluation.approvalEligibility()).isEqualTo("BLOCKED");
        assertThat(evaluation.reasons()).contains("POLICY_UNAPPROVED");
        verify(storage, never()).verifyBytes(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void digestMismatchAndStorageOutageHaveDifferentRecordedOutcomes() {
        Fixture mismatch = fixture();
        when(storage.verifyBytes(mismatch.key(), "etag", 20L * 1024 * 1024))
                .thenReturn(bytes("b".repeat(64)));
        assertThatThrownBy(() -> integrity.verify(mismatch.caseId(), input(mismatch), UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("HASH_MISMATCH"));
        assertThat(records.findByCaseId(mismatch.caseId(), org.springframework.data.domain.Pageable.unpaged())
                .getContent().getFirst().getVerificationStatus())
                .isEqualTo(EvidenceVerificationRecord.Status.HASH_MISMATCH);

        Fixture outage = fixture();
        when(storage.verifyBytes(outage.key(), "etag", 20L * 1024 * 1024))
                .thenThrow(new IllegalStateException("synthetic storage outage"));
        assertThatThrownBy(() -> integrity.verify(outage.caseId(), input(outage), UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(CaseApiException.class, error -> {
                    assertThat(error.code()).isEqualTo("DEPENDENCY_UNAVAILABLE");
                    assertThat(error.status()).isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
                });
        assertThat(records.findByCaseId(outage.caseId(), org.springframework.data.domain.Pageable.unpaged())
                .getContent().getFirst().getVerificationStatus())
                .isEqualTo(EvidenceVerificationRecord.Status.STORAGE_UNAVAILABLE);
    }

    @Test
    void sizeMismatchAndMissingObjectAreSeparateRecordedFailures() {
        Fixture wrongSize = fixture();
        when(storage.verifyBytes(wrongSize.key(), "etag", 20L * 1024 * 1024))
                .thenReturn(new EvidenceObjectStorage.ByteVerification(5, "image/png",
                        wrongSize.originalSha256(), "etag", EvidenceObjectStorage.MarkerKind.ETAG));
        assertThatThrownBy(() -> integrity.verify(wrongSize.caseId(), input(wrongSize),
                UUID.randomUUID().toString())).isInstanceOfSatisfying(CaseApiException.class,
                error -> assertThat(error.code()).isEqualTo("SIZE_MISMATCH"));
        assertThat(records.findByCaseId(wrongSize.caseId(), org.springframework.data.domain.Pageable.unpaged())
                .getContent().getFirst().getVerificationStatus())
                .isEqualTo(EvidenceVerificationRecord.Status.SIZE_MISMATCH);

        Fixture missing = fixture();
        when(storage.verifyBytes(missing.key(), "etag", 20L * 1024 * 1024))
                .thenThrow(S3Exception.builder().statusCode(404).build());
        assertThatThrownBy(() -> integrity.verify(missing.caseId(), input(missing),
                UUID.randomUUID().toString())).isInstanceOfSatisfying(CaseApiException.class,
                error -> assertThat(error.code()).isEqualTo("OBJECT_MISSING"));
        assertThat(records.findByCaseId(missing.caseId(), org.springframework.data.domain.Pageable.unpaged())
                .getContent().getFirst().getVerificationStatus())
                .isEqualTo(EvidenceVerificationRecord.Status.OBJECT_MISSING);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void naturalRetentionExpiryIsRecordedWithoutReadingPrivateBytes() throws Exception {
        LocalDateTime deadline = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(10);
        Fixture fixture = fixture(deadline);
        long waitMillis = Math.max(0, Duration.between(LocalDateTime.now(ZoneOffset.UTC), deadline).toMillis() + 20);
        Thread.sleep(waitMillis);

        assertThatThrownBy(() -> integrity.verify(fixture.caseId(), input(fixture), UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("RETENTION_EXPIRED"));
        assertThat(records.findByCaseId(fixture.caseId(), org.springframework.data.domain.Pageable.unpaged())
                .getContent().getFirst().getVerificationStatus())
                .isEqualTo(EvidenceVerificationRecord.Status.RETENTION_EXPIRED);
        verify(storage, never()).verifyBytes(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void wrongEvidenceAndUnauthorizedRoleCannotReadPrivateBytes() {
        Fixture fixture = fixture();
        clearInvocations(storage);
        as("analyst");
        assertThatThrownBy(() -> integrity.verify(fixture.caseId(), input(fixture), UUID.randomUUID().toString()))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        as("reviewer");
        var wrong = new EvidenceIntegrityDto.VerifyInput(input(fixture).expectedCaseVersion(),
                fixture.snapshotId(), fixture.sourceId(), UUID.randomUUID(), "Synthetic reason");
        assertThatThrownBy(() -> integrity.verify(fixture.caseId(), wrong, UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("RESOURCE_NOT_FOUND"));
        verify(storage, never()).verifyBytes(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void byteGetRunsBeforeWriteTransactionWithoutCheckedOutConnection() {
        Fixture fixture = fixture();
        when(storage.verifyBytes(fixture.key(), "etag", 20L * 1024 * 1024)).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(((com.zaxxer.hikari.HikariDataSource) dataSource)
                    .getHikariPoolMXBean().getActiveConnections()).isZero();
            return bytes(fixture.originalSha256());
        });
        assertThat(integrity.verify(fixture.caseId(), input(fixture), UUID.randomUUID().toString())
                .verificationStatus()).isEqualTo(EvidenceVerificationRecord.Status.MATCH);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void caseVersionChangeDuringByteReadRejectsWriteWithoutPartialAudit() {
        Fixture fixture = fixture();
        when(storage.verifyBytes(fixture.key(), "etag", 20L * 1024 * 1024)).thenAnswer(call -> {
            Case changed = cases.findById(fixture.caseId()).orElseThrow();
            changed.recordPreparation(LocalDateTime.now(ZoneOffset.UTC));
            cases.saveAndFlush(changed);
            return bytes(fixture.originalSha256());
        });

        assertThatThrownBy(() -> integrity.verify(fixture.caseId(), input(fixture), UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("CASE_VERSION_CONFLICT"));
        assertThat(records.findByCaseId(fixture.caseId(), org.springframework.data.domain.Pageable.unpaged()))
                .isEmpty();
        assertThat(activities.findByCaseId(fixture.caseId(), org.springframework.data.domain.Pageable.unpaged())
                .stream().filter(activity -> activity.getEventType().equals("EVIDENCE_BYTES_CHECKED"))).isEmpty();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void sourceMetadataChangeDuringByteReadRejectsWriteWithoutPartialAudit() {
        Fixture fixture = fixture();
        UUID reportId = assets.findById(fixture.evidenceId()).orElseThrow().getFanReportId();
        when(storage.verifyBytes(fixture.key(), "etag", 20L * 1024 * 1024)).thenAnswer(call -> {
            FanReport changed = reports.findById(reportId).orElseThrow();
            changed.setRetentionUntil(changed.getReceivedAt().plusDays(45));
            reports.saveAndFlush(changed);
            return bytes(fixture.originalSha256());
        });

        assertThatThrownBy(() -> integrity.verify(fixture.caseId(), input(fixture), UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("SOURCE_CHANGED"));
        assertThat(records.findByCaseId(fixture.caseId(), org.springframework.data.domain.Pageable.unpaged()))
                .isEmpty();
        assertThat(activities.findByCaseId(fixture.caseId(), org.springframework.data.domain.Pageable.unpaged())
                .stream().filter(activity -> activity.getEventType().equals("EVIDENCE_BYTES_CHECKED"))).isEmpty();
    }

    @Test
    void httpSecurityAndResponseDoNotExposeObjectKey() throws Exception {
        Fixture fixture = fixture();
        when(storage.verifyBytes(fixture.key(), "etag", 20L * 1024 * 1024))
                .thenReturn(bytes(fixture.originalSha256()));
        String url = "/api/v1/cases/" + fixture.caseId() + "/evidence-verifications";
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input(fixture))))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(url).header("Authorization", "Bearer analyst")
                .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input(fixture))))
                .andExpect(status().isForbidden());
        var response = mvc.perform(post(url).header("Authorization", "Bearer reviewer")
                .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input(fixture))))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.verificationStatus").value("MATCH")).andReturn();
        assertThat(response.getResponse().getContentAsString()).doesNotContain(fixture.key(), "etag", TENANT.toString());
        mvc.perform(get(url).header("Authorization", "Bearer reviewer"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
    }

    private Fixture fixture() {
        return fixture(null);
    }

    private Fixture fixture(LocalDateTime retentionUntil) {
        UUID reportId = UUID.randomUUID();
        FanReport report = new FanReport(reportId, "FR-" + reportId, TENANT, EVENT,
                LocalDateTime.now().plusDays(1), null, FanReport.ReportType.OTHER,
                "Synthetic report", null, null, null, null,
                LocalDateTime.now(ZoneOffset.UTC).minusDays(1), UUID.randomUUID().toString(),
                "d".repeat(64), "e".repeat(64));
        if (retentionUntil != null) report.setRetentionUntil(retentionUntil);
        report.markReceived();
        reports.saveAndFlush(report);
        UUID evidenceId = UUID.randomUUID();
        String objectKey = "fan-reports/" + reportId + "/" + evidenceId;
        String sha256 = sha256(new byte[]{1, 2, 3, 4});
        EvidenceAsset asset = new EvidenceAsset(evidenceId, reportId, objectKey, "image/png", 4,
                sha256, LocalDateTime.now(ZoneOffset.UTC), report.getRetentionUntil());
        asset.markStored("etag");
        assets.saveAndFlush(asset);
        when(storage.head(objectKey)).thenReturn(new EvidenceObjectStorage.ObjectMetadata(4, "image/png", "etag"));
        CaseDto.View created = caseService.create(new CaseDto.Create(EVENT, Case.Type.FAN_REPORT,
                List.of(), List.of(reportId), List.of(evidenceId), "Investigate synthetic report", null, null),
                UUID.randomUUID().toString());
        CaseDto.View assigned = caseService.assign(created.caseId(),
                new CaseDto.Assign(REVIEWER, created.caseVersion(), "Assign reviewer"), UUID.randomUUID().toString());
        CaseDto.View triaged = caseService.transition(created.caseId(),
                new CaseDto.Transition(Case.Status.TRIAGED, assigned.caseVersion(), "Triage case",
                        "Synthetic classification", null, null, null, null, null), UUID.randomUUID().toString());
        CaseDto.View reviewing = caseService.transition(created.caseId(),
                new CaseDto.Transition(Case.Status.IN_REVIEW, triaged.caseVersion(), "Begin review",
                        null, null, null, null, null, null), UUID.randomUUID().toString());
        var snapshot = preparation.capture(reviewing.caseId(),
                new CasePreparationDto.Capture(reviewing.caseVersion(), "Capture stored file"),
                UUID.randomUUID().toString());
        UUID sourceId = sources.findByCaseIdOrderByLinkedAtAscIdAsc(reviewing.caseId()).stream()
                .filter(ref -> ref.getSourceType() == CaseSourceReference.Type.EVIDENCE_ASSET)
                .findFirst().orElseThrow().getId();
        return new Fixture(reviewing.caseId(), snapshot.evidenceSnapshotId(), sourceId,
                evidenceId, objectKey, sha256);
    }

    private EvidenceIntegrityDto.VerifyInput input(Fixture fixture) {
        return new EvidenceIntegrityDto.VerifyInput(cases.findById(fixture.caseId()).orElseThrow().getCaseVersion(),
                fixture.snapshotId(), fixture.sourceId(), fixture.evidenceId(), "Check synthetic original");
    }

    private static EvidenceObjectStorage.ByteVerification bytes(String sha256) {
        return new EvidenceObjectStorage.ByteVerification(4, "image/png", sha256,
                "etag", EvidenceObjectStorage.MarkerKind.ETAG);
    }

    private void member(UUID id, String subject, AdminRole role) {
        memberships.save(new AdminMembershipEntity(id, TENANT, subject, AccessRecordStatus.ACTIVE));
        roles.save(new MembershipRole(UUID.randomUUID(), id, role));
        grants.save(new MembershipEventGrant(UUID.randomUUID(), id, TENANT, EVENT));
    }
    private void as(String subject) {
        JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt(subject));
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
    private static Jwt jwt(String subject) {
        return Jwt.withTokenValue(subject).header("alg", "RS256").subject(subject).build();
    }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private record Fixture(UUID caseId, UUID snapshotId, UUID sourceId, UUID evidenceId,
                           String key, String originalSha256) { }
}
