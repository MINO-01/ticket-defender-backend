package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.in.web.dto.CaseDto;
import com.ticket.defender_core.adapter.in.web.dto.CasePreparationDto;
import com.ticket.defender_core.adapter.out.persistence.*;
import com.ticket.defender_core.application.service.CaseApiException;
import com.ticket.defender_core.application.service.CasePreparationService;
import com.ticket.defender_core.application.service.CasePreparationWriteService;
import com.ticket.defender_core.application.service.CaseService;
import com.ticket.defender_core.application.service.CaseSnapshotManifest;
import com.ticket.defender_core.application.service.EvidenceObjectStorage;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import software.amazon.awssdk.services.s3.model.S3Exception;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:case-preparation;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false"
})
@AutoConfigureMockMvc
@Transactional
class CasePreparationIntegrationTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID EVENT = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID REVIEWER = UUID.fromString("66666666-6666-4666-8666-666666666666");

    @Autowired private CaseService caseService;
    @Autowired private CasePreparationService preparation;
    @Autowired private CasePreparationWriteService writer;
    @Autowired private CaseSnapshotManifest manifests;
    @Autowired private CaseRepository cases;
    @Autowired private CaseSourceReferenceRepository sources;
    @Autowired private CaseActivityRepository activities;
    @Autowired private CaseIdempotencyRepository idempotency;
    @Autowired private EvidenceSnapshotRepository snapshots;
    @Autowired private ReportVersionRepository drafts;
    @Autowired private TenantRepository tenants;
    @Autowired private TenantEventRepository events;
    @Autowired private AdminMembershipRepository memberships;
    @Autowired private MembershipRoleRepository roles;
    @Autowired private MembershipEventGrantRepository grants;
    @Autowired private FanReportRepository reports;
    @Autowired private EvidenceAssetRepository assets;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private jakarta.persistence.EntityManager entityManager;
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
    void captureAndDraftSealContentAndReplayWithoutChangingPastRows() {
        CaseDto.View target = reviewingCase(report(), report());
        CasePreparationDto.Capture capture = new CasePreparationDto.Capture(target.caseVersion(), "Capture stored sources");
        String captureKey = UUID.randomUUID().toString();
        CasePreparationDto.Snapshot first = preparation.capture(target.caseId(), capture, captureKey);
        assertThat(first.manifestSha256()).isEqualTo(CaseSnapshotManifest.sha256(first.manifestJson()));
        assertThat(first.sourceIntegrity()).isEqualTo("NO_FILE");
        assertThat(first.approvalEligible()).isFalse();
        assertThat(first.manifestJson()).doesNotContain("Synthetic report");
        assertThat(preparation.capture(target.caseId(), capture, captureKey)).isEqualTo(first);
        assertThat(idempotency.findByTenantIdAndActorMembershipIdAndOperationAndTargetCaseIdAndKeyDigest(
                TENANT, REVIEWER, "SNAPSHOT_CREATE", target.caseId(),
                CaseSnapshotManifest.sha256(captureKey)).orElseThrow().getResponseJson())
                .isEqualTo("{\"evidenceSnapshotId\":\"" + first.evidenceSnapshotId() + "\"}");
        assertThat(snapshots.findByCaseIdOrderByEvidenceRevisionDesc(target.caseId())).hasSize(1);
        assertThat(cases.findById(target.caseId()).orElseThrow().getEvidenceRevision()).isEqualTo(1);

        CasePreparationDto.DraftInput input = new CasePreparationDto.DraftInput(
                cases.findById(target.caseId()).orElseThrow().getCaseVersion(), first.evidenceSnapshotId(),
                "Synthetic title", "Synthetic assessment for reviewer", "Metadata only evidence",
                "Further review needed", "Prepare first draft");
        String draftKey = UUID.randomUUID().toString();
        CasePreparationDto.Draft draft = preparation.draft(target.caseId(), input, draftKey);
        assertThat(draft.revision()).isEqualTo(1);
        assertThat(draft.evidenceRevision()).isEqualTo(first.evidenceRevision());
        assertThat(draft.version()).isZero();
        assertThat(draft.contentSha256()).isEqualTo(CaseSnapshotManifest.sha256(draft.contentJson()));
        assertThat(draft.renderable()).isFalse();
        assertThat(draft.approvalEligible()).isFalse();
        assertThat(preparation.draft(target.caseId(), input, draftKey)).isEqualTo(draft);
        assertThat(idempotency.findByTenantIdAndActorMembershipIdAndOperationAndTargetCaseIdAndKeyDigest(
                TENANT, REVIEWER, "DRAFT_CREATE", target.caseId(),
                CaseSnapshotManifest.sha256(draftKey)).orElseThrow().getResponseJson())
                .isEqualTo("{\"reportVersionId\":\"" + draft.reportVersionId() + "\"}");
        CasePreparationDto.DraftInput revised = new CasePreparationDto.DraftInput(
                cases.findById(target.caseId()).orElseThrow().getCaseVersion(), first.evidenceSnapshotId(),
                input.title(), "Updated synthetic assessment for reviewer", input.limitations(),
                input.conclusion(), "Revise assessment");
        CasePreparationDto.Draft second = preparation.draft(target.caseId(), revised, UUID.randomUUID().toString());
        assertThat(second.revision()).isEqualTo(2);
        assertThat(preparation.draft(draft.reportVersionId()).contentSha256())
                .isEqualTo(draft.contentSha256());
        assertThat(activities.findByCaseId(target.caseId(), org.springframework.data.domain.Pageable.unpaged())
                .stream().filter(activity -> activity.getEventType().equals("EVIDENCE_SNAPSHOT_CAPTURED"))).hasSize(1);
        assertThat(activities.findByCaseId(target.caseId(), org.springframework.data.domain.Pageable.unpaged())
                .stream().filter(activity -> activity.getEventType().equals("REPORT_DRAFT_CREATED"))).hasSize(2);
        mvcGetDraft(draft.reportVersionId());
    }

    private void mvcGetDraft(UUID draftId) {
        try {
            mvc.perform(get("/api/v1/report-versions/" + draftId)
                    .header("Authorization", "Bearer reviewer"))
                    .andExpect(status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.status").value("DRAFT"));
            mvc.perform(get("/api/v1/report-versions/" + draftId)
                    .header("Authorization", "Bearer approver"))
                    .andExpect(status().isForbidden());
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    @Test
    void sourceOrderDoesNotChangeManifestDigest() {
        CaseDto.View target = reviewingCase(report(), report());
        Case entity = cases.findById(target.caseId()).orElseThrow();
        var refs = sources.findByCaseIdOrderByLinkedAtAscIdAsc(target.caseId());
        var reversed = new java.util.ArrayList<>(refs);
        java.util.Collections.reverse(reversed);
        var first = manifests.capture(entity, refs, LocalDateTime.now());
        var second = manifests.capture(entity, reversed, LocalDateTime.now());
        assertThat(second.sha256()).isEqualTo(first.sha256());
    }

    @Test
    void persistedSnapshotManifestIsNotUpdatedInPlace() {
        CaseDto.View target = reviewingCase(report());
        CasePreparationDto.Snapshot captured = preparation.capture(target.caseId(),
                new CasePreparationDto.Capture(target.caseVersion(), "Capture source"),
                UUID.randomUUID().toString());
        EvidenceSnapshot entity = snapshots.findById(captured.evidenceSnapshotId()).orElseThrow();
        org.springframework.test.util.ReflectionTestUtils.setField(entity, "manifestJson", "{}");
        snapshots.saveAndFlush(entity);
        entityManager.clear();
        assertThat(snapshots.findById(captured.evidenceSnapshotId()).orElseThrow().getManifestJson())
                .isEqualTo(captured.manifestJson());
    }

    @Test
    void snapshotCreationRejectsCrossEventReferenceAndDraftRejectsAnotherCaseSnapshot() {
        CaseDto.View first = reviewingCase(report());
        CaseDto.View second = reviewingCase(report());
        var captured = preparation.capture(first.caseId(),
                new CasePreparationDto.Capture(first.caseVersion(), "Capture first source"),
                UUID.randomUUID().toString());
        assertThatThrownBy(() -> preparation.draft(second.caseId(), new CasePreparationDto.DraftInput(
                second.caseVersion(), captured.evidenceSnapshotId(), "Synthetic title",
                "Synthetic assessment for reviewer", "Metadata only", "Further review needed",
                "Prepare draft"), UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("RESOURCE_NOT_FOUND"));
        CaseSourceReference reference = sources.findByCaseIdOrderByLinkedAtAscIdAsc(second.caseId()).getFirst();
        org.springframework.test.util.ReflectionTestUtils.setField(reference, "eventId", UUID.randomUUID());
        sources.saveAndFlush(reference);
        assertThatThrownBy(() -> preparation.capture(second.caseId(),
                new CasePreparationDto.Capture(second.caseVersion(), "Capture wrong scope"),
                UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("SOURCE_CHANGED"));
    }

    /**
     * XML 1.0 허용 문자 중 직렬화 결과가 가장 큰 문자는 Java 문자 하나당 3바이트(U+0800 이상 BMP, 예: 한글)다.
     * 네 필드 최대 길이 합 12160자로도 약 36.5KB라서 writer의 60000바이트 제한에는 입력 검증을 통과한 값으로
     * 도달할 수 없다. 그 제한은 방어용으로 남겨 두고, 여기서는 실제로 도달 가능한 최대 길이 경계만 검증한다.
     */
    @Test
    void draftAcceptsMaximumLengthFieldsAndRejectsOneCharacterBeyond() {
        CaseDto.View target = reviewingCase(report());
        var snapshot = preparation.capture(target.caseId(),
                new CasePreparationDto.Capture(target.caseVersion(), "Capture source"),
                UUID.randomUUID().toString());
        long expected = cases.findById(target.caseId()).orElseThrow().getCaseVersion();
        String title = "가".repeat(160);
        String assessment = "가".repeat(6000);
        String limitations = "가".repeat(3000);
        String conclusion = "가".repeat(3000);
        // 제목 161자 초과는 XML 문자 거부 테스트에서 이미 검증한다.
        List<String[]> beyond = List.of(
                new String[]{title, assessment + "가", limitations, conclusion},
                new String[]{title, assessment, limitations + "가", conclusion},
                new String[]{title, assessment, limitations, conclusion + "가"});
        for (String[] fields : beyond) {
            assertThatThrownBy(() -> preparation.draft(target.caseId(), new CasePreparationDto.DraftInput(
                    expected, snapshot.evidenceSnapshotId(), fields[0], fields[1], fields[2], fields[3],
                    "Oversized field input"), UUID.randomUUID().toString()))
                    .isInstanceOfSatisfying(CaseApiException.class,
                            error -> assertThat(error.code()).isEqualTo("VALIDATION_FAILED"));
        }
        assertThat(drafts.findByCaseIdOrderByRevisionDesc(target.caseId())).isEmpty();

        CasePreparationDto.Draft draft = preparation.draft(target.caseId(), new CasePreparationDto.DraftInput(
                expected, snapshot.evidenceSnapshotId(), title, assessment, limitations, conclusion,
                "Maximum field input"), UUID.randomUUID().toString());
        int storedBytes = draft.contentJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertThat(storedBytes).isGreaterThanOrEqualTo(12160 * 3).isLessThan(60000);
        assertThat(draft.contentSha256()).isEqualTo(CaseSnapshotManifest.sha256(draft.contentJson()));
        var stored = json.readTree(draft.contentJson());
        assertThat(stored.get("title").asString()).isEqualTo(title);
        assertThat(stored.get("assessment").asString()).isEqualTo(assessment);
        assertThat(stored.get("limitations").asString()).isEqualTo(limitations);
        assertThat(stored.get("conclusion").asString()).isEqualTo(conclusion);
    }

    /**
     * 공식 PDF는 보고서 본문을 XHTML에 넣어 렌더링하므로, XML 1.0에서 허용하지 않는 문자가 봉인되면
     * 승인 뒤에도 발급이 영구 실패한다. 작성 시점에 거부되고 부분 기록이 남지 않아야 한다.
     */
    @Test
    void draftRejectsCharactersOutsideXml10WithoutPartialWrites() {
        CaseDto.View target = reviewingCase(report());
        var snapshot = preparation.capture(target.caseId(),
                new CasePreparationDto.Capture(target.caseVersion(), "Capture source"),
                UUID.randomUUID().toString());
        long expected = cases.findById(target.caseId()).orElseThrow().getCaseVersion();
        long activitiesBefore = activities.findByCaseId(target.caseId(),
                org.springframework.data.domain.Pageable.unpaged()).getTotalElements();
        String valid = "합성 평가 본문입니다";
        // trim()이 앞뒤 제어문자를 지우므로 앞에 둔 경우도 원문 기준으로 거부해야 한다.
        List<String[]> rejected = List.of(
                new String[]{"\u0001합성 제목", valid, "한계 사항", "결론 문장"},
                new String[]{"합성 제목", "합성 평가\u0000 본문입니다", "한계 사항", "결론 문장"},
                new String[]{"합성 제목", valid, "한계 사항\u001F", "결론 문장"},
                new String[]{"합성 제목", valid, "한계 사항", "결론 문장\u000B"},
                new String[]{"합성 제목", valid + "\uFFFE", "한계 사항", "결론 문장"},
                new String[]{"합성 제목", valid, "한계 사항\uFFFF", "결론 문장"},
                new String[]{"합성 \uD800제목", valid, "한계 사항", "결론 문장"},
                new String[]{"합성 제목", valid + "\uDC00", "한계 사항", "결론 문장"},
                new String[]{"합성 제목", valid, "한계 사항", "결론 문장\uDE00\uD83D"},
                new String[]{"ab", valid, "한계 사항", "결론 문장"},
                new String[]{"가".repeat(161), valid, "한계 사항", "결론 문장"},
                new String[]{"합성 제목", "짧은 평가", "한계 사항", "결론 문장"});
        for (String[] fields : rejected) {
            String key = UUID.randomUUID().toString();
            assertThatThrownBy(() -> preparation.draft(target.caseId(), new CasePreparationDto.DraftInput(
                    expected, snapshot.evidenceSnapshotId(), fields[0], fields[1], fields[2], fields[3],
                    "Synthetic draft reason"), key))
                    .isInstanceOfSatisfying(CaseApiException.class, error -> {
                        assertThat(error.status().value()).isEqualTo(422);
                        assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
                    });
            assertThat(idempotency.findByTenantIdAndActorMembershipIdAndOperationAndTargetCaseIdAndKeyDigest(
                    TENANT, REVIEWER, "DRAFT_CREATE", target.caseId(), CaseSnapshotManifest.sha256(key))).isEmpty();
        }
        // 서비스 사전 검사를 거치지 않는 writer 직접 호출도 같은 규칙으로 막혀야 한다.
        var current = cases.findById(target.caseId()).orElseThrow();
        var checked = manifests.capture(current, sources.findByCaseIdOrderByLinkedAtAscIdAsc(target.caseId()),
                LocalDateTime.now(java.time.ZoneOffset.UTC));
        assertThatThrownBy(() -> writer.draft(target.caseId(), new CasePreparationDto.DraftInput(
                expected, snapshot.evidenceSnapshotId(), "합성 제목", "합성 평가\u0007 본문입니다", "한계 사항", "결론 문장",
                "Synthetic draft reason"), REVIEWER, CaseSnapshotManifest.sha256(UUID.randomUUID().toString()),
                "e".repeat(64), checked))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("VALIDATION_FAILED"));
        entityManager.flush();
        entityManager.clear();
        assertThat(drafts.findByCaseIdOrderByRevisionDesc(target.caseId())).isEmpty();
        Case after = cases.findById(target.caseId()).orElseThrow();
        assertThat(after.getCaseVersion()).isEqualTo(expected);
        assertThat(after.getStatus()).isEqualTo(Case.Status.IN_REVIEW);
        assertThat(activities.findByCaseId(target.caseId(), org.springframework.data.domain.Pageable.unpaged())
                .getTotalElements()).isEqualTo(activitiesBefore);
    }

    @Test
    void draftAcceptsKoreanWhitespaceTabsNewlinesAndSupplementaryCharacters() {
        CaseDto.View target = reviewingCase(report());
        var snapshot = preparation.capture(target.caseId(),
                new CasePreparationDto.Capture(target.caseVersion(), "Capture source"),
                UUID.randomUUID().toString());
        long expected = cases.findById(target.caseId()).orElseThrow().getCaseVersion();
        String assessment = "첫째 줄\t탭 구분\n둘째 줄\r\n셋째 줄 \uD83D\uDE00 보충 문자";
        CasePreparationDto.Draft draft = preparation.draft(target.caseId(), new CasePreparationDto.DraftInput(
                expected, snapshot.evidenceSnapshotId(), "가".repeat(160), assessment, "한계 사항\n주의", "결론 문장",
                "Synthetic draft reason"), UUID.randomUUID().toString());
        assertThat(draft.revision()).isEqualTo(1);
        // 원문을 바꾸지 않고 기존 규칙대로 앞뒤 공백만 정리한 값이 봉인된다.
        assertThat(json.readTree(draft.contentJson()).get("assessment").asString()).isEqualTo(assessment);
        assertThat(draft.contentSha256()).isEqualTo(CaseSnapshotManifest.sha256(draft.contentJson()));
    }

    @Test
    void draftRouteReturns422ForCharactersOutsideXml10() throws Exception {
        CaseDto.View target = reviewingCase(report());
        var snapshot = preparation.capture(target.caseId(),
                new CasePreparationDto.Capture(target.caseVersion(), "Capture source"),
                UUID.randomUUID().toString());
        long expected = cases.findById(target.caseId()).orElseThrow().getCaseVersion();
        // 단독 surrogate는 JSON 직렬화기를 거치지 않도록 escape 문자열로 직접 보낸다.
        for (String assessment : List.of("합성 평가\\u0001 본문입니다", "합성 평가 본문입니다\\ud800")) {
            String body = "{\"expectedCaseVersion\":" + expected + ",\"evidenceSnapshotId\":\""
                    + snapshot.evidenceSnapshotId() + "\",\"title\":\"합성 제목\",\"assessment\":\""
                    + assessment + "\",\"limitations\":\"한계 사항\",\"conclusion\":\"결론 문장\","
                    + "\"reason\":\"Prepare draft\"}";
            mvc.perform(post("/api/v1/cases/" + target.caseId() + "/report-versions")
                            .header("Authorization", "Bearer reviewer")
                            .header("Idempotency-Key", UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        assertThat(drafts.findByCaseIdOrderByRevisionDesc(target.caseId())).isEmpty();
        assertThat(cases.findById(target.caseId()).orElseThrow().getCaseVersion()).isEqualTo(expected);
    }

    @Test
    void reviewerAndVersionAndIdempotencyChecksProtectWrites() {
        CaseDto.View target = reviewingCase(report());
        var input = new CasePreparationDto.Capture(target.caseVersion(), "Capture synthetic source");
        as("analyst");
        assertThatThrownBy(() -> preparation.capture(target.caseId(), input, UUID.randomUUID().toString()))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        as("reviewer");
        String key = UUID.randomUUID().toString();
        preparation.capture(target.caseId(), input, key);
        assertThatThrownBy(() -> preparation.capture(target.caseId(),
                new CasePreparationDto.Capture(input.expectedCaseVersion(), "Different reason"), key))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        assertThatThrownBy(() -> preparation.capture(target.caseId(), input, UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("CASE_VERSION_CONFLICT"));
    }

    @Test
    void expiredReportIsRejectedAtCapture() {
        FanReport source = report();
        CaseDto.View target = reviewingCase(source);
        source.setRetentionUntil(LocalDateTime.now(java.time.ZoneOffset.UTC).minusHours(1));
        reports.saveAndFlush(source);
        assertThatThrownBy(() -> preparation.capture(target.caseId(),
                new CasePreparationDto.Capture(target.caseVersion(), "Capture source"),
                UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("SOURCE_NOT_AVAILABLE"));
        assertThat(snapshots.findByCaseIdOrderByEvidenceRevisionDesc(target.caseId())).isEmpty();
    }

    @Test
    void storedAssetRequiresFreshPrivateHeadAndNeverClaimsOriginalImmutability() {
        FanReport report = report();
        UUID evidenceId = UUID.randomUUID();
        String objectKey = "fan-reports/synthetic/" + evidenceId;
        EvidenceAsset asset = new EvidenceAsset(evidenceId, report.getFanReportId(), objectKey,
                "image/png", 4, "a".repeat(64), LocalDateTime.now(), report.getRetentionUntil());
        asset.markStored("opaque-v1");
        assets.saveAndFlush(asset);
        when(storage.head(objectKey)).thenReturn(new EvidenceObjectStorage.ObjectMetadata(4, "image/png", "opaque-v1"));
        CaseDto.View target = reviewingAssetCase(report, evidenceId);
        CasePreparationDto.Snapshot snapshot = preparation.capture(target.caseId(),
                new CasePreparationDto.Capture(target.caseVersion(), "Capture stored file"),
                UUID.randomUUID().toString());
        assertThat(snapshot.sourceIntegrity()).isEqualTo("METADATA_ONLY");
        assertThat(snapshot.approvalEligible()).isFalse();
        assertThat(snapshot.manifestJson()).contains("a".repeat(64)).doesNotContain(objectKey);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void evidenceHeadRunsOutsideWriteTransactionAndWithoutCheckedOutConnection() throws Exception {
        FanReport report = report();
        UUID evidenceId = UUID.randomUUID();
        String key = "fan-reports/synthetic/" + evidenceId;
        EvidenceAsset asset = new EvidenceAsset(evidenceId, report.getFanReportId(), key,
                "image/png", 4, "a".repeat(64), LocalDateTime.now(), report.getRetentionUntil());
        asset.markStored("opaque-v1");
        assets.saveAndFlush(asset);
        when(storage.head(key)).thenReturn(new EvidenceObjectStorage.ObjectMetadata(4, "image/png", "opaque-v1"));
        CaseDto.View target = reviewingAssetCase(report, evidenceId);
        AtomicInteger headCalls = new AtomicInteger();
        when(storage.head(key)).thenAnswer(call -> {
            headCalls.incrementAndGet();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(((com.zaxxer.hikari.HikariDataSource) dataSource)
                    .getHikariPoolMXBean().getActiveConnections()).isZero();
            return new EvidenceObjectStorage.ObjectMetadata(4, "image/png", "opaque-v1");
        });
        var result = mvc.perform(post("/api/v1/cases/" + target.caseId() + "/evidence-snapshots")
                        .header("Authorization", "Bearer reviewer")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new CasePreparationDto.Capture(
                                target.caseVersion(), "Capture stored file"))))
                .andExpect(status().isCreated()).andReturn();
        var snapshot = json.readValue(result.getResponse().getContentAsString(), CasePreparationDto.Snapshot.class);
        long currentVersion = cases.findById(target.caseId()).orElseThrow().getCaseVersion();
        mvc.perform(post("/api/v1/cases/" + target.caseId() + "/report-versions")
                        .header("Authorization", "Bearer reviewer")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new CasePreparationDto.DraftInput(currentVersion,
                                snapshot.evidenceSnapshotId(), "Synthetic title", "Synthetic assessment for reviewer",
                                "Metadata only", "Further review needed", "Prepare draft"))))
                .andExpect(status().isCreated());
        assertThat(headCalls).hasValue(2);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void caseVersionChangeAfterPreflightLeavesNoPartialSnapshot() {
        CaseDto.View target = reviewingCase(report());
        Case entity = cases.findById(target.caseId()).orElseThrow();
        var checked = manifests.capture(entity, sources.findByCaseIdOrderByLinkedAtAscIdAsc(target.caseId()),
                LocalDateTime.now(java.time.ZoneOffset.UTC));
        entity.transition(Case.Status.NEEDS_EVIDENCE, null, LocalDateTime.now(java.time.ZoneOffset.UTC));
        cases.saveAndFlush(entity);
        String key = UUID.randomUUID().toString();
        assertThatThrownBy(() -> writer.capture(target.caseId(),
                new CasePreparationDto.Capture(target.caseVersion(), "Capture source"), REVIEWER,
                CaseSnapshotManifest.sha256(key), "d".repeat(64), checked))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("CASE_VERSION_CONFLICT"));
        assertThat(snapshots.findByCaseIdOrderByEvidenceRevisionDesc(target.caseId())).isEmpty();
        assertThat(activities.findByCaseId(target.caseId(), org.springframework.data.domain.Pageable.unpaged())
                .stream().filter(activity -> activity.getEventType().equals("EVIDENCE_SNAPSHOT_CAPTURED"))).isEmpty();
        assertThat(idempotency.findByTenantIdAndActorMembershipIdAndOperationAndTargetCaseIdAndKeyDigest(
                TENANT, REVIEWER, "SNAPSHOT_CREATE", target.caseId(), CaseSnapshotManifest.sha256(key))).isEmpty();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void changedAssetMetadataAfterPreflightIsRejectedWithoutAnotherHead() {
        FanReport report = report();
        UUID evidenceId = UUID.randomUUID();
        String objectKey = "fan-reports/synthetic/" + evidenceId;
        EvidenceAsset asset = new EvidenceAsset(evidenceId, report.getFanReportId(), objectKey,
                "image/png", 4, "a".repeat(64), LocalDateTime.now(), report.getRetentionUntil());
        asset.markStored("opaque-v1");
        assets.saveAndFlush(asset);
        when(storage.head(objectKey)).thenReturn(new EvidenceObjectStorage.ObjectMetadata(4, "image/png", "opaque-v1"));
        CaseDto.View target = reviewingAssetCase(report, evidenceId);
        var checked = manifests.capture(cases.findById(target.caseId()).orElseThrow(),
                sources.findByCaseIdOrderByLinkedAtAscIdAsc(target.caseId()),
                LocalDateTime.now(java.time.ZoneOffset.UTC));
        org.springframework.test.util.ReflectionTestUtils.setField(asset, "sha256", "b".repeat(64));
        assets.saveAndFlush(asset);
        clearInvocations(storage);
        assertThatThrownBy(() -> writer.capture(target.caseId(),
                new CasePreparationDto.Capture(target.caseVersion(), "Capture source"), REVIEWER,
                CaseSnapshotManifest.sha256(UUID.randomUUID().toString()), "d".repeat(64), checked))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("SOURCE_CHANGED"));
        org.springframework.test.util.ReflectionTestUtils.setField(asset, "sha256", "a".repeat(64));
        org.springframework.test.util.ReflectionTestUtils.setField(asset, "objectKey", objectKey + "-changed");
        assets.saveAndFlush(asset);
        assertThatThrownBy(() -> writer.capture(target.caseId(),
                new CasePreparationDto.Capture(target.caseVersion(), "Capture source"), REVIEWER,
                CaseSnapshotManifest.sha256(UUID.randomUUID().toString()), "d".repeat(64), checked))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("SOURCE_CHANGED"));
        verify(storage, never()).head(objectKey);
        assertThat(snapshots.findByCaseIdOrderByEvidenceRevisionDesc(target.caseId())).isEmpty();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void expiredReportAfterDraftPreflightDoesNotCreateRevisionOrActivity() {
        FanReport report = report();
        CaseDto.View target = reviewingCase(report);
        var snapshot = preparation.capture(target.caseId(),
                new CasePreparationDto.Capture(target.caseVersion(), "Capture source"),
                UUID.randomUUID().toString());
        var current = cases.findById(target.caseId()).orElseThrow();
        var checked = manifests.capture(current, sources.findByCaseIdOrderByLinkedAtAscIdAsc(target.caseId()),
                LocalDateTime.now(java.time.ZoneOffset.UTC));
        report.setRetentionUntil(LocalDateTime.now(java.time.ZoneOffset.UTC).minusHours(1));
        reports.saveAndFlush(report);
        assertThatThrownBy(() -> writer.draft(target.caseId(), new CasePreparationDto.DraftInput(
                current.getCaseVersion(), snapshot.evidenceSnapshotId(), "Synthetic title",
                "Synthetic assessment for reviewer", "Metadata only", "Further review needed",
                "Prepare draft"), REVIEWER, CaseSnapshotManifest.sha256(UUID.randomUUID().toString()),
                "d".repeat(64), checked))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("SOURCE_NOT_AVAILABLE"));
        assertThat(drafts.findByCaseIdOrderByRevisionDesc(target.caseId())).isEmpty();
        assertThat(activities.findByCaseId(target.caseId(), org.springframework.data.domain.Pageable.unpaged())
                .stream().filter(activity -> activity.getEventType().equals("REPORT_DRAFT_CREATED"))).isEmpty();
    }

    @Test
    void missingObjectAndStorageFailureAreDifferentAndDoNotCreateSnapshot() {
        FanReport report = report();
        UUID evidenceId = UUID.randomUUID();
        String key = "fan-reports/synthetic/" + evidenceId;
        EvidenceAsset asset = new EvidenceAsset(evidenceId, report.getFanReportId(), key,
                "image/png", 4, "a".repeat(64), LocalDateTime.now(), report.getRetentionUntil());
        asset.markStored("opaque-v1");
        assets.saveAndFlush(asset);
        when(storage.head(key)).thenReturn(new EvidenceObjectStorage.ObjectMetadata(4, "image/png", "opaque-v1"));
        CaseDto.View target = reviewingAssetCase(report, evidenceId);
        doThrow(S3Exception.builder().statusCode(404).build()).when(storage).head(key);
        var input = new CasePreparationDto.Capture(target.caseVersion(), "Capture stored file");
        assertThatThrownBy(() -> preparation.capture(target.caseId(), input, UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("SOURCE_OBJECT_MISSING"));
        doThrow(new IllegalStateException("Synthetic storage outage")).when(storage).head(key);
        assertThatThrownBy(() -> preparation.capture(target.caseId(), input, UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(CaseApiException.class,
                        error -> assertThat(error.code()).isEqualTo("DEPENDENCY_UNAVAILABLE"));
        assertThat(snapshots.findByCaseIdOrderByEvidenceRevisionDesc(target.caseId())).isEmpty();
    }

    @Test
    void httpRoutesKeepPrivateBoundaries() throws Exception {
        CaseDto.View target = reviewingCase(report());
        String url = "/api/v1/cases/" + target.caseId() + "/evidence-snapshots";
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                new CasePreparationDto.Capture(target.caseVersion(), "Capture source"))))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(url).header("Authorization", "Bearer analyst")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                        new CasePreparationDto.Capture(target.caseVersion(), "Capture source"))))
                .andExpect(status().isForbidden());
        mvc.perform(post(url).header("Authorization", "Bearer reviewer")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                        new CasePreparationDto.Capture(target.caseVersion(), "Capture source"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.approvalEligible").value(false));
        mvc.perform(get(url).header("Authorization", "Bearer approver"))
                .andExpect(status().isForbidden());
        mvc.perform(get(url).header("Authorization", "Bearer reviewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].manifestSchemaVersion").value(1));
        mvc.perform(get(url).param("pageSize", "0")
                .header("Authorization", "Bearer reviewer"))
                .andExpect(status().isBadRequest());
    }

    private CaseDto.View reviewingCase(FanReport... sourceReports) {
        return reviewingCaseWithAssets(java.util.Arrays.stream(sourceReports).toList(), List.of());
    }

    private CaseDto.View reviewingAssetCase(FanReport report, UUID evidenceId) {
        return reviewingCaseWithAssets(List.of(report), List.of(evidenceId));
    }

    private CaseDto.View reviewingCaseWithAssets(List<FanReport> sourceReports, List<UUID> evidenceIds) {
        CaseDto.View created = caseService.create(new CaseDto.Create(EVENT, Case.Type.FAN_REPORT,
                List.of(), sourceReports.stream().map(FanReport::getFanReportId).toList(),
                evidenceIds, "Investigate synthetic report", null, null), UUID.randomUUID().toString());
        CaseDto.View assigned = caseService.assign(created.caseId(),
                new CaseDto.Assign(REVIEWER, created.caseVersion(), "Assign reviewer"), UUID.randomUUID().toString());
        CaseDto.View triaged = caseService.transition(created.caseId(),
                new CaseDto.Transition(Case.Status.TRIAGED, assigned.caseVersion(), "Triage case",
                        "Synthetic classification", null, null, null, null, null), UUID.randomUUID().toString());
        return caseService.transition(created.caseId(),
                new CaseDto.Transition(Case.Status.IN_REVIEW, triaged.caseVersion(), "Begin review",
                        null, null, null, null, null, null), UUID.randomUUID().toString());
    }

    private FanReport report() {
        UUID id = UUID.randomUUID();
        FanReport report = new FanReport(id, "FR-" + id, TENANT, EVENT,
                LocalDateTime.now().plusDays(1), null, FanReport.ReportType.OTHER,
                "Synthetic report", null, null, null, null,
                LocalDateTime.now(java.time.ZoneOffset.UTC).minusDays(1),
                UUID.randomUUID().toString(), "d".repeat(64), "e".repeat(64));
        report.markReceived();
        return reports.saveAndFlush(report);
    }

    private void member(UUID id, String subject, AdminRole role) {
        memberships.save(new AdminMembershipEntity(id, TENANT, subject, AccessRecordStatus.ACTIVE));
        roles.save(new MembershipRole(UUID.randomUUID(), id, role));
        grants.save(new MembershipEventGrant(UUID.randomUUID(), id, TENANT, EVENT));
    }

    private void as(String subject) {
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt(subject));
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private static Jwt jwt(String subject) {
        return Jwt.withTokenValue(subject).header("alg", "RS256").subject(subject).build();
    }
}
