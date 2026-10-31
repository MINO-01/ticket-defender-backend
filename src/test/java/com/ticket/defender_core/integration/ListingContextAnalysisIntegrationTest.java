package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.out.api.ListingContextClient;
import com.ticket.defender_core.adapter.out.persistence.*;
import com.ticket.defender_core.application.service.EvidenceObjectStorage;
import com.ticket.defender_core.domain.*;
import com.ticket.defender_core.security.AdminRole;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Pageable;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import software.amazon.awssdk.services.s3.model.S3Exception;
import tools.jackson.databind.JsonNode;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:listing-analysis;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false",
        "ticket-defender.track2-listing-analysis.enabled=true",
        "ticket-defender.track2-listing-analysis.internal-key=" + FakeListingContextServer.INTERNAL_KEY,
        "ticket-defender.track2-listing-analysis.read-timeout=PT1S"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ListingContextAnalysisIntegrationTest {
    private static final FakeListingContextServer PYTHON = start();
    private static final long LIMIT = 5L * 1024 * 1024;

    @Autowired private MockMvc mvc;
    @Autowired private TenantRepository tenants;
    @Autowired private TenantEventRepository events;
    @Autowired private AdminMembershipRepository memberships;
    @Autowired private MembershipRoleRepository roles;
    @Autowired private MembershipEventGrantRepository grants;
    @Autowired private FanReportRepository reports;
    @Autowired private EvidenceAssetRepository assets;
    @Autowired private ListingContextAnalysisRepository analyses;
    @Autowired private ListingContextObservationRepository observations;
    @MockitoBean(name = "adminJwtDecoder") private JwtDecoder decoder;
    @MockitoBean private EvidenceObjectStorage storage;
    @MockitoSpyBean private ListingContextClient client;

    private UUID tenant;
    private UUID event;
    private String analyst;

    @DynamicPropertySource
    static void python(DynamicPropertyRegistry registry) {
        registry.add("ticket-defender.track2-listing-analysis.base-url", PYTHON::baseUrl);
    }

    @AfterAll
    static void stop() {
        PYTHON.close();
    }

    @BeforeEach
    void setup() {
        given(decoder.decode(anyString())).willAnswer(call -> Jwt.withTokenValue(call.getArgument(0))
                .header("alg", "RS256").subject(call.getArgument(0)).build());
        PYTHON.received.clear();
        PYTHON.responder = body -> FakeListingContextServer.success(body, "COMPLETED",
                FakeListingContextServer.completedFields());
        tenant = UUID.randomUUID();
        event = UUID.randomUUID();
        tenants.save(new Tenant(tenant, "Synthetic tenant", AccessRecordStatus.ACTIVE));
        events.save(new TenantEvent(event, tenant, "synthetic-" + event, "Synthetic show", AccessRecordStatus.ACTIVE));
        analyst = member(tenant, event, AdminRole.ANALYST);
    }

    @Test
    void verifiedEvidenceIsAnalyzedStoredWithEveryFieldStateAndSameKeyReplays(CapturedOutput output) throws Exception {
        Fixture fixture = fixture();
        String key = UUID.randomUUID().toString();

        String body = analyze(fixture, key, analyst).andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.executionStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.analysisStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.reviewRequired").value(true))
                .andExpect(jsonPath("$.modelName").value("fake-test-provider"))
                .andExpect(jsonPath("$.modelVersion").value("test-v1"))
                .andExpect(jsonPath("$.input.verifiedSha256").value(fixture.sha256()))
                .andExpect(jsonPath("$.fields.saleText.state").value("FOUND"))
                .andExpect(jsonPath("$.fields.saleText.value").value("양도합니다"))
                .andExpect(jsonPath("$.fields.askingPrice.value.raw").value("45만원"))
                .andExpect(jsonPath("$.fields.askingPrice.value.amount").value("450000"))
                .andExpect(jsonPath("$.fields.zone.state").value("AMBIGUOUS"))
                .andExpect(jsonPath("$.fields.zone.candidates[1]").value("B"))
                .andExpect(jsonPath("$.fields.row.unknownReason").value("UNREADABLE"))
                .andExpect(jsonPath("$.fields.seat.unknownReason").value("NOT_CONFIRMED"))
                .andExpect(jsonPath("$.fields.currency.unknownReason").value("ABSENT"))
                .andExpect(jsonPath("$.candidateMatch.status").value("NOT_LINKED"))
                .andExpect(jsonPath("$.candidateMatch.reason").value("PERFORMANCE_REF_UNVERIFIED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(fixture.objectKey(), "etag", FakeListingContextServer.INTERNAL_KEY);

        assertThat(PYTHON.received).hasSize(1);
        FakeListingContextServer.Received sent = PYTHON.received.getFirst();
        assertThat(sent.headers().get("x-td-internal-key")).isEqualTo(FakeListingContextServer.INTERNAL_KEY);
        assertThat(sent.body().keySet()).containsExactly("requestId", "evidenceId", "imageSha256", "mimeType",
                "imageBase64");
        assertThat(sent.headers().get("x-request-id")).isEqualTo(sent.body().get("requestId"));
        assertThat(sent.body().get("evidenceId")).isEqualTo(fixture.evidenceId().toString());
        assertThat(sent.body().get("imageSha256")).isEqualTo(fixture.sha256());
        assertThat(sent.body().get("mimeType")).isEqualTo("image/png");
        assertThat(Base64.getDecoder().decode((String) sent.body().get("imageBase64"))).isEqualTo(fixture.bytes());

        ListingContextAnalysis stored = analyses.findAll().stream()
                .filter(row -> row.getEvidenceId().equals(fixture.evidenceId())).findFirst().orElseThrow();
        assertThat(stored.getTenantId()).isEqualTo(tenant);
        assertThat(stored.getEventId()).isEqualTo(event);
        assertThat(stored.getActiveEvidenceId()).isNull();
        assertThat(stored.getObjectMarker()).isEqualTo("etag");
        assertThat(stored.getRequestId().toString()).isEqualTo(sent.body().get("requestId"));
        assertThat(observations.findByAnalysisIdIn(List.of(stored.getId()))).hasSize(9);

        analyze(fixture, key, analyst).andExpect(status().isCreated())
                .andExpect(jsonPath("$.analysisId").value(stored.getId().toString()));
        assertThat(PYTHON.received).hasSize(1);
        assertThat(output.getAll()).doesNotContain(FakeListingContextServer.INTERNAL_KEY,
                ((String) sent.body().get("imageBase64")).substring(0, 40), fixture.objectKey());
    }

    @Test
    void partialAndUnverifiableResultsStayDistinctAndRetriesKeepHistory() throws Exception {
        Fixture fixture = fixture();
        Map<String, Object> partial = FakeListingContextServer.unknownFields();
        partial.put("eventName", FakeListingContextServer.found("가상 콘서트", "가상 콘서트"));
        PYTHON.responder = body -> FakeListingContextServer.success(body, "PARTIAL", partial);
        analyze(fixture, UUID.randomUUID().toString(), analyst).andExpect(status().isCreated())
                .andExpect(jsonPath("$.analysisStatus").value("PARTIAL"))
                .andExpect(jsonPath("$.candidateMatch.reason").value("PERFORMANCE_REF_UNVERIFIED"));
        PYTHON.responder = body -> FakeListingContextServer.success(body, "UNVERIFIABLE",
                FakeListingContextServer.unknownFields());
        analyze(fixture, UUID.randomUUID().toString(), analyst).andExpect(status().isCreated())
                .andExpect(jsonPath("$.analysisStatus").value("UNVERIFIABLE"))
                .andExpect(jsonPath("$.fields.saleText.state").value("UNKNOWN"));
        PYTHON.responder = body -> FakeListingContextServer.problem(503, "MODEL_UNAVAILABLE", body);
        analyze(fixture, UUID.randomUUID().toString(), analyst).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ANALYZER_UNAVAILABLE"));

        String page = mvc.perform(get(url(fixture)).header("Authorization", "Bearer " + analyst))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode items = FakeListingContextServer.JSON.readTree(page).get("items");
        assertThat(items.size()).isEqualTo(3);
        assertThat(items.get(0).get("analysisStatus").stringValue()).isEqualTo("FAILED");
        assertThat(items.get(0).get("analyzerErrorCode").stringValue()).isEqualTo("MODEL_UNAVAILABLE");
        assertThat(items.get(0).get("fields").isNull()).isTrue();
        assertThat(items.get(1).get("analysisStatus").stringValue()).isEqualTo("UNVERIFIABLE");
        assertThat(items.get(2).get("analysisStatus").stringValue()).isEqualTo("PARTIAL");
        assertThat(items.get(2).get("fields").get("eventName").get("state").stringValue()).isEqualTo("FOUND");
    }

    @Test
    void analyzerFailuresAreStoredAsFailedWithoutObservations() throws Exception {
        Fixture fixture = fixture();
        PYTHON.responder = body -> FakeListingContextServer.problem(504, "MODEL_TIMEOUT", body);
        analyze(fixture, UUID.randomUUID().toString(), analyst).andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("ANALYZER_MODEL_TIMEOUT"));
        PYTHON.responder = body -> FakeListingContextServer.problem(502, "MODEL_RESPONSE_INVALID", body);
        analyze(fixture, UUID.randomUUID().toString(), analyst).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("ANALYZER_MODEL_FAILED"));
        PYTHON.responder = body -> new FakeListingContextServer.Reply(200,
                FakeListingContextServer.success(body, "COMPLETED", FakeListingContextServer.completedFields())
                        .body(), 1500);
        analyze(fixture, UUID.randomUUID().toString(), analyst).andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("ANALYZER_TIMEOUT"));
        PYTHON.responder = body -> {
            Map<String, Object> wrong = new java.util.LinkedHashMap<>(body);
            wrong.put("evidenceId", UUID.randomUUID().toString());
            return FakeListingContextServer.success(wrong, "COMPLETED", FakeListingContextServer.completedFields());
        };
        analyze(fixture, UUID.randomUUID().toString(), analyst).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("ANALYZER_IDENTITY_MISMATCH"));
        PYTHON.responder = body -> FakeListingContextServer.success(body, "COMPLETED",
                FakeListingContextServer.unknownFields());
        analyze(fixture, UUID.randomUUID().toString(), analyst).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("ANALYZER_RESPONSE_INVALID"));

        List<ListingContextAnalysis> rows = rows(fixture);
        assertThat(rows).hasSize(5).allSatisfy(row -> {
            assertThat(row.getExecutionStatus()).isEqualTo(ListingContextAnalysis.Execution.ANALYZER_FAILED);
            assertThat(row.getAnalysisStatus()).isEqualTo(ListingContextAnalysis.AnalysisStatus.FAILED);
            assertThat(row.getModelName()).isNull();
            assertThat(row.getActiveEvidenceId()).isNull();
            assertThat(row.getCandidateMatchReason()).isEqualTo("ANALYSIS_NOT_SUCCEEDED");
        });
        assertThat(observations.findByAnalysisIdIn(rows.stream().map(ListingContextAnalysis::getId).toList()))
                .isEmpty();
    }

    @Test
    void wrongInternalKeyIsRecordedAsAnalyzerAuthenticationFailure() throws Exception {
        Fixture fixture = fixture();
        ListingContextClient wrongKey = new ListingContextClient(true, PYTHON.baseUrl(),
                "wrong-synthetic-internal-key-0123456789abcdef", java.time.Duration.ofSeconds(1),
                java.time.Duration.ofSeconds(1));
        doAnswer(call -> wrongKey.analyze(call.getArgument(0), call.getArgument(1), call.getArgument(2),
                call.getArgument(3), call.getArgument(4))).when(client).analyze(any(), any(), anyString(), anyString(),
                any());
        try {
            analyze(fixture, UUID.randomUUID().toString(), analyst).andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.code").value("ANALYZER_AUTH_FAILED"));
        } finally {
            reset(client);
        }
        ListingContextAnalysis row = rows(fixture).getFirst();
        assertThat(row.getAnalyzerHttpStatus()).isEqualTo(401);
        assertThat(row.getAnalyzerErrorCode()).isEqualTo("INTERNAL_AUTH_REQUIRED");
    }

    @Test
    void integrityAndInputFailuresAreRecordedWithoutCallingAnalyzer() throws Exception {
        Fixture mismatch = fixture();
        when(storage.readVerifiedBytes(mismatch.objectKey(), "etag", LIMIT)).thenReturn(verified(
                mismatch.bytes(), "b".repeat(64), "image/png"));
        analyze(mismatch, UUID.randomUUID().toString(), analyst).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("HASH_MISMATCH"));

        Fixture missing = fixture();
        when(storage.readVerifiedBytes(missing.objectKey(), "etag", LIMIT))
                .thenThrow(S3Exception.builder().statusCode(404).build());
        analyze(missing, UUID.randomUUID().toString(), analyst).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OBJECT_MISSING"));

        Fixture outage = fixture();
        when(storage.readVerifiedBytes(outage.objectKey(), "etag", LIMIT))
                .thenThrow(new IllegalStateException("synthetic storage outage"));
        analyze(outage, UUID.randomUUID().toString(), analyst).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"));

        Fixture disguised = fixture();
        byte[] jpeg = image("jpg");
        when(storage.readVerifiedBytes(disguised.objectKey(), "etag", LIMIT)).thenReturn(verified(
                jpeg, sha256(jpeg), "image/png"));
        analyze(disguised, UUID.randomUUID().toString(), analyst).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SIZE_MISMATCH"));

        Fixture large = fixture(LIMIT + 1, "image/png", null);
        analyze(large, UUID.randomUUID().toString(), analyst).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ANALYSIS_INPUT_TOO_LARGE"));
        verify(storage, never()).readVerifiedBytes(org.mockito.ArgumentMatchers.eq(large.objectKey()), anyString(),
                anyLong());

        Fixture webp = fixture(4, "image/webp", null);
        analyze(webp, UUID.randomUUID().toString(), analyst).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_IMAGE_TYPE"));

        Fixture expired = fixture(4, "image/png", LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        analyze(expired, UUID.randomUUID().toString(), analyst).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RETENTION_EXPIRED"));

        assertThat(PYTHON.received).isEmpty();
        assertThat(rows(mismatch).getFirst().getAnalysisStatus()).isEqualTo(ListingContextAnalysis.AnalysisStatus.FAILED);
        assertThat(rows(outage).getFirst().getExecutionStatus())
                .isEqualTo(ListingContextAnalysis.Execution.STORAGE_FAILED);
        assertThat(rows(large).getFirst().getExecutionStatus())
                .isEqualTo(ListingContextAnalysis.Execution.INPUT_REJECTED);
        assertThat(reports.findById(large.fanReportId()).orElseThrow().getIntakeStatus())
                .isEqualTo(FanReport.IntakeStatus.RECEIVED);
    }

    @Test
    void sameSizeDisguisedImageIsRejectedAsMediaTypeMismatch() throws Exception {
        byte[] jpeg = image("jpg");
        Fixture disguised = fixture(jpeg.length, "image/png", null);
        when(storage.readVerifiedBytes(disguised.objectKey(), "etag", LIMIT)).thenReturn(verified(
                jpeg, disguised.sha256(), "image/png"));
        analyze(disguised, UUID.randomUUID().toString(), analyst).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MEDIA_TYPE_MISMATCH"));
        assertThat(PYTHON.received).isEmpty();
    }

    @Test
    void scopeOwnershipAndRolesAreEnforcedWithoutRecordingAttempts() throws Exception {
        Fixture fixture = fixture();
        Fixture sibling = fixture();
        UUID otherTenant = UUID.randomUUID();
        UUID otherEvent = UUID.randomUUID();
        tenants.save(new Tenant(otherTenant, "Other tenant", AccessRecordStatus.ACTIVE));
        events.save(new TenantEvent(otherEvent, otherTenant, "other-" + otherEvent, "Other", AccessRecordStatus.ACTIVE));
        String outsider = member(otherTenant, otherEvent, AdminRole.ANALYST);
        UUID secondEvent = UUID.randomUUID();
        events.save(new TenantEvent(secondEvent, tenant, "second-" + secondEvent, "Second", AccessRecordStatus.ACTIVE));
        String otherEventAnalyst = member(tenant, secondEvent, AdminRole.ANALYST);
        String approver = member(tenant, event, AdminRole.APPROVER);

        analyze(fixture, UUID.randomUUID().toString(), outsider).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        analyze(fixture, UUID.randomUUID().toString(), otherEventAnalyst).andExpect(status().isNotFound());
        analyze(fixture, UUID.randomUUID().toString(), approver).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/fan-reports/" + fixture.fanReportId() + "/evidence/"
                        + sibling.evidenceId() + "/listing-analyses").header("Authorization", "Bearer " + analyst)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(post(url(fixture)).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized());
        analyze(fixture, "not-a-uuid", analyst).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
        mvc.perform(get(url(fixture)).header("Authorization", "Bearer " + outsider)).andExpect(status().isNotFound());

        assertThat(rows(fixture)).isEmpty();
        assertThat(PYTHON.received).isEmpty();
        verify(storage, never()).readVerifiedBytes(anyString(), anyString(), anyLong());
    }

    @Test
    void missingAnalyzerConfigurationBlocksBeforeStorageOrRecording() throws Exception {
        Fixture fixture = fixture();
        doReturn(false).when(client).configured();
        try {
            analyze(fixture, UUID.randomUUID().toString(), analyst).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("ANALYZER_DISABLED"));
        } finally {
            reset(client);
        }
        assertThat(rows(fixture)).isEmpty();
        verify(storage, never()).readVerifiedBytes(anyString(), anyString(), anyLong());
    }

    @Test
    void activeAttemptBlocksOtherKeysUntilLeaseExpiresAndAbandonedAttemptIsNotReused() throws Exception {
        Fixture fixture = fixture();
        UUID membership = memberships.findBySubjectAndTenantId(analyst, tenant).orElseThrow().getId();
        String staleKey = UUID.randomUUID().toString();
        ListingContextAnalysis active = analyses.saveAndFlush(new ListingContextAnalysis(UUID.randomUUID(), tenant,
                event, fixture.fanReportId(), fixture.evidenceId(), membership,
                com.ticket.defender_core.application.service.CaseSnapshotManifest.sha256(staleKey), UUID.randomUUID(),
                "c".repeat(64), "etag", fixture.sha256(), fixture.bytes().length, "image/png",
                LocalDateTime.now(ZoneOffset.UTC), LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5)));
        analyze(fixture, UUID.randomUUID().toString(), analyst).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANALYSIS_IN_PROGRESS"));
        analyze(fixture, staleKey, analyst).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANALYSIS_IN_PROGRESS"));
        assertThat(PYTHON.received).isEmpty();

        org.springframework.test.util.ReflectionTestUtils.setField(active, "leaseExpiresAt",
                LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        analyses.saveAndFlush(active);
        analyze(fixture, UUID.randomUUID().toString(), analyst).andExpect(status().isCreated())
                .andExpect(jsonPath("$.analysisStatus").value("COMPLETED"));
        ListingContextAnalysis abandoned = analyses.findById(active.getId()).orElseThrow();
        assertThat(abandoned.getExecutionStatus()).isEqualTo(ListingContextAnalysis.Execution.ABANDONED);
        assertThat(abandoned.getErrorCode()).isEqualTo("ANALYSIS_ABANDONED");
        analyze(fixture, staleKey, analyst).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANALYSIS_ABANDONED"));
        assertThat(PYTHON.received).hasSize(1);
    }

    @Test
    void concurrentRequestsForSameEvidenceRunOnlyOneAnalysis() throws Exception {
        Fixture fixture = fixture();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PYTHON.responder = body -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return FakeListingContextServer.success(body, "COMPLETED", FakeListingContextServer.completedFields());
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            String sharedKey = UUID.randomUUID().toString();
            Future<Integer> first = pool.submit(() -> analyze(fixture, sharedKey, analyst)
                    .andReturn().getResponse().getStatus());
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Integer> sameKey = pool.submit(() -> analyze(fixture, sharedKey, analyst)
                    .andReturn().getResponse().getStatus());
            assertThat(sameKey.get(5, TimeUnit.SECONDS)).isEqualTo(409);
            assertThat(analyze(fixture, UUID.randomUUID().toString(), analyst).andReturn().getResponse().getStatus())
                    .isEqualTo(409);
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(201);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(PYTHON.received).hasSize(1);
        assertThat(rows(fixture)).hasSize(1).first()
                .satisfies(row -> assertThat(row.getActiveEvidenceId()).isNull());
        analyze(fixture, UUID.randomUUID().toString(), analyst).andExpect(status().isCreated());
        assertThat(rows(fixture)).hasSize(2);
    }

    private ResultActions analyze(Fixture fixture, String key, String subject) throws Exception {
        return mvc.perform(post(url(fixture)).header("Authorization", "Bearer " + subject)
                .header("Idempotency-Key", key));
    }

    private static String url(Fixture fixture) {
        return "/api/v1/admin/fan-reports/" + fixture.fanReportId() + "/evidence/" + fixture.evidenceId()
                + "/listing-analyses";
    }

    private List<ListingContextAnalysis> rows(Fixture fixture) {
        return analyses.findByFanReportIdAndEvidenceId(fixture.fanReportId(), fixture.evidenceId(), Pageable.unpaged())
                .getContent();
    }

    private Fixture fixture() throws IOException {
        byte[] bytes = image("png");
        return fixture(bytes, bytes.length, "image/png", null);
    }

    private Fixture fixture(long byteSize, String mediaType, LocalDateTime retentionUntil) throws IOException {
        return fixture(image("png"), byteSize, mediaType, retentionUntil);
    }

    private Fixture fixture(byte[] bytes, long byteSize, String mediaType, LocalDateTime retentionUntil) {
        UUID reportId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        FanReport report = new FanReport(reportId, "FR-" + reportId, tenant, event, now.plusDays(1), null,
                FanReport.ReportType.PREMIUM_RESALE, "Synthetic listing report", null, null, null, null,
                now.minusDays(1), UUID.randomUUID().toString(), "d".repeat(64), "e".repeat(64));
        report.markReceived();
        reports.saveAndFlush(report);
        UUID evidenceId = UUID.randomUUID();
        String objectKey = "fan-reports/" + reportId + "/" + evidenceId;
        String digest = sha256(bytes);
        EvidenceAsset asset = new EvidenceAsset(evidenceId, reportId, objectKey, mediaType, byteSize, digest,
                now.minusDays(1), retentionUntil == null ? report.getRetentionUntil() : retentionUntil);
        asset.markStored("etag");
        assets.saveAndFlush(asset);
        when(storage.readVerifiedBytes(objectKey, "etag", LIMIT)).thenReturn(verified(bytes, digest, mediaType));
        return new Fixture(reportId, evidenceId, objectKey, digest, bytes);
    }

    private static EvidenceObjectStorage.VerifiedBytes verified(byte[] bytes, String sha256, String mediaType) {
        return new EvidenceObjectStorage.VerifiedBytes(new EvidenceObjectStorage.ByteVerification(bytes.length,
                mediaType, sha256, "etag", EvidenceObjectStorage.MarkerKind.ETAG), bytes);
    }

    private String member(UUID tenantId, UUID eventId, AdminRole role) {
        UUID id = UUID.randomUUID();
        String subject = "listing-" + role.name().toLowerCase() + "-" + id;
        memberships.save(new AdminMembershipEntity(id, tenantId, subject, AccessRecordStatus.ACTIVE));
        roles.save(new MembershipRole(UUID.randomUUID(), id, role));
        grants.save(new MembershipEventGrant(UUID.randomUUID(), id, tenantId, eventId));
        return subject;
    }

    private static byte[] image(String format) throws IOException {
        BufferedImage picture = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        picture.setRGB(1, 1, 0x00336699);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(picture, format, output);
        return output.toByteArray();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static FakeListingContextServer start() {
        try {
            return new FakeListingContextServer();
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private record Fixture(UUID fanReportId, UUID evidenceId, String objectKey, String sha256, byte[] bytes) { }
}
