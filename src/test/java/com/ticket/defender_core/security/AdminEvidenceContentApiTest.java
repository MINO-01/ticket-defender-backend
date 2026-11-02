package com.ticket.defender_core.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.ticket.defender_core.adapter.out.persistence.AdminMembershipRepository;
import com.ticket.defender_core.adapter.out.persistence.EvidenceAccessAuditEventRepository;
import com.ticket.defender_core.adapter.out.persistence.EvidenceAssetRepository;
import com.ticket.defender_core.adapter.out.persistence.FanReportRepository;
import com.ticket.defender_core.adapter.out.persistence.MembershipEventGrantRepository;
import com.ticket.defender_core.adapter.out.persistence.MembershipRoleRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantEventRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantRepository;
import com.ticket.defender_core.adapter.out.storage.S3EvidenceObjectStorage;
import com.ticket.defender_core.application.service.EvidenceObjectStorage;
import com.ticket.defender_core.domain.AccessRecordStatus;
import com.ticket.defender_core.domain.AdminMembershipEntity;
import com.ticket.defender_core.domain.EvidenceAccessAuditEvent;
import com.ticket.defender_core.domain.EvidenceAsset;
import com.ticket.defender_core.domain.FanReport;
import com.ticket.defender_core.domain.MembershipEventGrant;
import com.ticket.defender_core.domain.MembershipRole;
import com.ticket.defender_core.domain.Tenant;
import com.ticket.defender_core.domain.TenantEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 원본 이미지 열람 API의 인증, 범위, 원본 검증, 감사 기록을 H2와 저장소 Mock으로 검증합니다.
 * 실제 MinIO와 MySQL 검증은 AdminEvidenceContentMinioMySqlTest가 담당합니다.
 * 감사 기록은 별도 트랜잭션으로 커밋되므로 테스트 트랜잭션을 쓰지 않고 테스트마다 새 ID를 사용합니다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:admin-evidence-content-api;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "ticket-defender.evidence-content.max-bytes=4096",
        "ticket-defender.evidence-content.max-concurrent-reads=1"
})
@AutoConfigureMockMvc
class AdminEvidenceContentApiTest {
    private static final String ADMIN_ISSUER = "https://issuer.example.test/admin";
    private static final String ADMIN_AUDIENCE = "ticket-defender-admin";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0, 16, 'J', 'F', 'I', 'F'};
    private static KeyPair keyPair;

    @Autowired private MockMvc mvc;
    @Autowired private TenantRepository tenants;
    @Autowired private TenantEventRepository events;
    @Autowired private AdminMembershipRepository memberships;
    @Autowired private MembershipRoleRepository roles;
    @Autowired private MembershipEventGrantRepository grants;
    @Autowired private FanReportRepository reports;
    @Autowired private EvidenceAssetRepository assets;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private EvidenceAccessAuditEventRepository audits;
    @MockitoBean private EvidenceObjectStorage storage;
    @MockitoBean(name = "adminJwtDecoder") private JwtDecoder adminDecoder;

    private UUID tenantA;
    private UUID tenantB;
    private UUID eventA;
    private UUID eventB;
    private String run;

    @BeforeAll
    static void createKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
    }

    @BeforeEach
    void seed() {
        reset(audits, storage);
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keyPair.getPublic()).build();
        decoder.setJwtValidator(JwtDecoderFactory.validator(ADMIN_ISSUER, ADMIN_AUDIENCE, null));
        given(adminDecoder.decode(anyString())).willAnswer(call -> decoder.decode(call.getArgument(0)));
        run = UUID.randomUUID().toString().substring(0, 8);
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        eventA = UUID.randomUUID();
        eventB = UUID.randomUUID();
        tenants.saveAndFlush(new Tenant(tenantA, "합성 고객사 A", AccessRecordStatus.ACTIVE));
        tenants.saveAndFlush(new Tenant(tenantB, "합성 고객사 B", AccessRecordStatus.ACTIVE));
        events.saveAndFlush(new TenantEvent(eventA, tenantA, "show-" + run, "공연 A", AccessRecordStatus.ACTIVE));
        events.saveAndFlush(new TenantEvent(eventB, tenantB, "show-" + run, "공연 B", AccessRecordStatus.ACTIVE));
        member(tenantA, eventA, "analyst", AdminRole.ANALYST);
        member(tenantA, eventA, "reviewer", AdminRole.REVIEWER);
        member(tenantA, eventA, "approver", AdminRole.APPROVER);
        member(tenantA, eventA, "tenant-admin", AdminRole.TENANT_ADMIN);
        member(tenantA, eventA, "no-role", null);
        member(tenantB, eventB, "reviewer-b", AdminRole.REVIEWER);
    }

    @Test
    void analystAndReviewerReceiveVerifiedPngAndJpegWithSafeHeaders() throws Exception {
        UUID report = report(tenantA, eventA, LocalDateTime.now(ZoneOffset.UTC));
        UUID png = asset(report, "image/png", PNG, true);
        UUID jpeg = asset(report, "image/jpeg", JPEG, true);
        String pngKey = assets.findById(png).orElseThrow().getObjectKey();
        verified(png, PNG, "image/png");
        verified(jpeg, JPEG, "image/jpeg");

        MvcResult first = mvc.perform(read(report, png).param("tenantId", tenantB.toString())
                        .param("objectKey", "fan-reports/other/secret").header(HttpHeaders.AUTHORIZATION, bearer("analyst")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/png"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"evidence-" + png + ".png\""))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, PNG.length))
                .andExpect(content().bytes(PNG))
                .andReturn();
        // 클라이언트가 보낸 tenantId와 objectKey는 무시하고 서버에 저장된 키만 사용합니다.
        verify(storage).readVerifiedBytes(eq(pngKey), eq("marker-" + png), eq(4096L));
        assertThat(headerValues(first)).noneMatch(value -> value.contains(pngKey) || value.contains("marker-"));
        mvc.perform(read(report, jpeg).header(HttpHeaders.AUTHORIZATION, bearer("reviewer")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/jpeg"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"evidence-" + jpeg + ".jpg\""))
                .andExpect(content().bytes(JPEG));

        List<EvidenceAccessAuditEvent> stored = auditsOf(report);
        assertThat(stored).hasSize(2).allSatisfy(row -> {
            assertThat(row.getOutcome()).isEqualTo(EvidenceAccessAuditEvent.Outcome.SUCCEEDED);
            assertThat(row.getDetailCode()).isEqualTo("VERIFIED_FOR_RESPONSE");
            assertThat(row.getTenantId()).isEqualTo(tenantA);
            assertThat(row.getEventId()).isEqualTo(eventA);
        });
        assertThat(stored).extracting(EvidenceAccessAuditEvent::getEvidenceId).containsExactlyInAnyOrder(png, jpeg);
        // 감사 행에는 객체 키, 객체 버전, 이미지 바이트, 토큰이 들어가지 않습니다.
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT * FROM evidence_access_audit_events WHERE fan_report_id=?", report.toString())) {
            assertThat(row.toString()).doesNotContain(pngKey, "marker-", "Bearer", "PNG", "fan-reports/");
            assertThat(row.keySet()).map(String::toLowerCase)
                    .containsExactlyInAnyOrder("id", "tenant_id", "event_id", "fan_report_id", "evidence_id",
                            "actor_membership_id", "outcome", "detail_code", "occurred_at");
        }
    }

    @Test
    void authenticationRoleScopeAndTenantSelectionFailuresDoNotRevealOrReadEvidence() throws Exception {
        UUID report = report(tenantA, eventA, LocalDateTime.now(ZoneOffset.UTC));
        UUID evidence = asset(report, "image/png", PNG, true);
        UUID otherReport = report(tenantA, eventA, LocalDateTime.now(ZoneOffset.UTC));
        UUID otherEvidence = asset(otherReport, "image/png", PNG, true);
        UUID tenantBReport = report(tenantB, eventB, LocalDateTime.now(ZoneOffset.UTC));
        UUID tenantBEvidence = asset(tenantBReport, "image/png", PNG, true);

        mvc.perform(read(report, evidence)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/fan-reports/{reportId}/evidence/not-a-uuid/content", report)
                        .header(HttpHeaders.AUTHORIZATION, bearer("analyst")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(read(report, evidence).header(HttpHeaders.AUTHORIZATION, "Bearer broken"))
                .andExpect(status().isUnauthorized());
        for (String subject : List.of("no-role", "approver", "tenant-admin"))
            mvc.perform(read(report, evidence).header(HttpHeaders.AUTHORIZATION, bearer(subject)))
                    .andExpect(status().isForbidden());
        mvc.perform(read(UUID.randomUUID(), evidence).header(HttpHeaders.AUTHORIZATION, bearer("analyst")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(read(tenantBReport, tenantBEvidence).header(HttpHeaders.AUTHORIZATION, bearer("analyst")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(read(report, tenantBEvidence).header(HttpHeaders.AUTHORIZATION, bearer("analyst")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(read(report, otherEvidence).header(HttpHeaders.AUTHORIZATION, bearer("analyst")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(read(report, UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, bearer("analyst")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        // 다중 고객사 정책 DEC-02가 정해지기 전까지 여러 활성 고객사 멤버십은 409로 막습니다.
        UUID extraTenant = UUID.randomUUID();
        tenants.saveAndFlush(new Tenant(extraTenant, "추가 고객사", AccessRecordStatus.ACTIVE));
        UUID extra = UUID.randomUUID();
        memberships.saveAndFlush(new AdminMembershipEntity(extra, extraTenant, "analyst-" + run,
                AccessRecordStatus.ACTIVE));
        roles.saveAndFlush(new MembershipRole(UUID.randomUUID(), extra, AdminRole.ANALYST));
        mvc.perform(read(report, evidence).header(HttpHeaders.AUTHORIZATION, bearer("analyst")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TENANT_SELECTION_REQUIRED"));

        verify(storage, never()).readVerifiedBytes(anyString(), any(), anyLong());
        // 제보 고객사 멤버십과 공연 권한이 확인된 관리자의 거부(403, 409)와 증거 소속 불일치(404)만 감사합니다.
        // 401과 범위 밖 404는 기록할 관리자 범위가 없으므로 남기지 않고, 다른 고객사 증거 ID도 저장하지 않습니다.
        List<EvidenceAccessAuditEvent> stored = auditsOf(report);
        assertThat(stored).allSatisfy(row ->
                assertThat(row.getOutcome()).isEqualTo(EvidenceAccessAuditEvent.Outcome.FAILED));
        assertThat(stored).extracting(EvidenceAccessAuditEvent::getDetailCode).containsExactlyInAnyOrder(
                "ACCESS_DENIED", "ACCESS_DENIED", "ACCESS_DENIED", "RESOURCE_NOT_FOUND", "RESOURCE_NOT_FOUND",
                "RESOURCE_NOT_FOUND", "TENANT_SELECTION_REQUIRED");
        assertThat(stored).filteredOn(row -> row.getDetailCode().equals("RESOURCE_NOT_FOUND"))
                .allSatisfy(row -> assertThat(row.getEvidenceId()).isNull());
        assertThat(stored).filteredOn(row -> !row.getDetailCode().equals("RESOURCE_NOT_FOUND"))
                .allSatisfy(row -> assertThat(row.getEvidenceId()).isEqualTo(evidence));
        assertThat(stored).allSatisfy(row -> assertThat(row.getTenantId()).isEqualTo(tenantA));
        assertThat(auditsOf(tenantBReport)).isEmpty();
        assertThat(auditsOf(otherReport)).isEmpty();
    }

    @Test
    void storageStateRetentionAndSizeAreCheckedBeforeReadingTheOriginal() throws Exception {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        UUID received = report(tenantA, eventA, now);
        UUID pendingAsset = asset(received, "image/png", PNG, false);
        UUID failedAsset = asset(received, "image/png", PNG, false);
        EvidenceAsset failed = assets.findById(failedAsset).orElseThrow();
        failed.markFailed();
        assets.saveAndFlush(failed);
        UUID pendingReport = reportWithStatus(tenantA, eventA, now, null);
        UUID pendingReportAsset = asset(pendingReport, "image/png", PNG, true);
        UUID failedReport = reportWithStatus(tenantA, eventA, now, "FAILED");
        UUID failedReportAsset = asset(failedReport, "image/png", PNG, true);
        UUID expiredReport = report(tenantA, eventA, now.minusDays(91));
        UUID expiredReportAsset = asset(expiredReport, "image/png", PNG, true);
        UUID expiredAsset = asset(received, "image/png", PNG, true);
        jdbc.update("UPDATE evidence_assets SET retention_until=? WHERE id=?", now.minusMinutes(1),
                expiredAsset.toString());
        UUID gif = asset(received, "image/gif", PNG, true);
        UUID large = asset(received, "image/png", new byte[4097], true);

        expect(received, pendingAsset, 422, "SOURCE_NOT_AVAILABLE");
        expect(received, failedAsset, 422, "SOURCE_NOT_AVAILABLE");
        expect(pendingReport, pendingReportAsset, 422, "SOURCE_NOT_AVAILABLE");
        expect(failedReport, failedReportAsset, 422, "SOURCE_NOT_AVAILABLE");
        expect(expiredReport, expiredReportAsset, 422, "RETENTION_EXPIRED");
        expect(received, expiredAsset, 422, "RETENTION_EXPIRED");
        expect(received, gif, 422, "UNSUPPORTED_IMAGE_TYPE");
        expect(received, large, 422, "EVIDENCE_CONTENT_TOO_LARGE");
        verify(storage, never()).readVerifiedBytes(anyString(), any(), anyLong());
        assertThat(auditsOf(received)).hasSize(5)
                .allSatisfy(row -> assertThat(row.getOutcome()).isEqualTo(EvidenceAccessAuditEvent.Outcome.FAILED));
        assertThat(auditsOf(expiredReport)).singleElement()
                .satisfies(row -> assertThat(row.getDetailCode()).isEqualTo("RETENTION_EXPIRED"));
    }

    @Test
    void integrityAndStorageFailuresNeverReturnImageBytes() throws Exception {
        UUID report = report(tenantA, eventA, LocalDateTime.now(ZoneOffset.UTC));
        UUID changed = asset(report, "image/png", PNG, true);
        given(storage.readVerifiedBytes(eq(key(changed)), any(), anyLong()))
                .willThrow(new S3EvidenceObjectStorage.ObjectMarkerChangedException());
        UUID precondition = asset(report, "image/png", PNG, true);
        given(storage.readVerifiedBytes(eq(key(precondition)), any(), anyLong()))
                .willThrow(S3Exception.builder().statusCode(412).build());
        UUID tooLarge = asset(report, "image/png", PNG, true);
        given(storage.readVerifiedBytes(eq(key(tooLarge)), any(), anyLong()))
                .willThrow(new S3EvidenceObjectStorage.ObjectSizeExceededException());
        UUID missing = asset(report, "image/png", PNG, true);
        given(storage.readVerifiedBytes(eq(key(missing)), any(), anyLong()))
                .willThrow(S3Exception.builder().statusCode(404).build());
        UUID outage = asset(report, "image/png", PNG, true);
        given(storage.readVerifiedBytes(eq(key(outage)), any(), anyLong()))
                .willThrow(S3Exception.builder().statusCode(503).message("internal endpoint secret").build());
        UUID hash = asset(report, "image/png", PNG, true);
        byte[] tampered = PNG.clone();
        tampered[15] = 'X';
        given(storage.readVerifiedBytes(eq(key(hash)), any(), anyLong())).willReturn(bytes(tampered, "image/png"));
        UUID size = asset(report, "image/png", PNG, true);
        byte[] shorter = java.util.Arrays.copyOf(PNG, PNG.length - 1);
        given(storage.readVerifiedBytes(eq(key(size)), any(), anyLong())).willReturn(bytes(shorter, "image/png"));
        UUID sniffed = asset(report, "image/jpeg", PNG, true);
        given(storage.readVerifiedBytes(eq(key(sniffed)), any(), anyLong())).willReturn(bytes(PNG, "image/jpeg"));
        UUID header = asset(report, "image/png", PNG, true);
        given(storage.readVerifiedBytes(eq(key(header)), any(), anyLong())).willReturn(bytes(PNG, "image/jpeg"));

        expect(report, changed, 422, "OBJECT_CHANGED");
        expect(report, precondition, 422, "OBJECT_CHANGED");
        expect(report, tooLarge, 422, "EVIDENCE_CONTENT_TOO_LARGE");
        expect(report, missing, 422, "OBJECT_MISSING");
        expect(report, hash, 422, "HASH_MISMATCH");
        expect(report, size, 422, "SIZE_MISMATCH");
        expect(report, sniffed, 422, "MEDIA_TYPE_MISMATCH");
        expect(report, header, 422, "OBJECT_CHANGED");
        MvcResult unavailable = expect(report, outage, 503, "STORAGE_UNAVAILABLE");
        assertThat(unavailable.getResponse().getContentAsString()).doesNotContain("internal endpoint secret");
        // 버킷 자체가 응답하지 않으면 객체 없음이 아니라 저장소 장애로 분류합니다.
        willThrow(S3Exception.builder().statusCode(503).build()).given(storage).checkReady();
        expect(report, missing, 503, "STORAGE_UNAVAILABLE");

        assertThat(auditsOf(report)).hasSize(10).allSatisfy(row ->
                assertThat(row.getOutcome()).isEqualTo(EvidenceAccessAuditEvent.Outcome.FAILED));
        assertThat(auditsOf(report)).extracting(EvidenceAccessAuditEvent::getDetailCode)
                .contains("OBJECT_CHANGED", "OBJECT_MISSING", "HASH_MISMATCH", "SIZE_MISMATCH",
                        "MEDIA_TYPE_MISMATCH", "STORAGE_UNAVAILABLE", "EVIDENCE_CONTENT_TOO_LARGE");
    }

    @Test
    void successIsNotReturnedWithoutTheSuccessAuditAndFailedAuditKeepsTheOriginalError() throws Exception {
        UUID report = report(tenantA, eventA, LocalDateTime.now(ZoneOffset.UTC));
        UUID evidence = asset(report, "image/png", PNG, true);
        verified(evidence, PNG, "image/png");
        UUID hash = asset(report, "image/png", PNG, true);
        byte[] tampered = PNG.clone();
        tampered[15] = 'X';
        given(storage.readVerifiedBytes(eq(key(hash)), any(), anyLong())).willReturn(bytes(tampered, "image/png"));
        willThrow(new DataAccessResourceFailureException("audit store down")).given(audits).saveAndFlush(any());

        MvcResult blocked = expect(report, evidence, 503, "AUDIT_UNAVAILABLE");
        assertThat(blocked.getResponse().getContentType()).doesNotStartWith("image/");
        assertThat(blocked.getResponse().getContentAsString()).doesNotContain("audit store down");
        expect(report, hash, 422, "HASH_MISMATCH");
        assertThat(auditsOf(report)).isEmpty();

        reset(audits);
        mvc.perform(read(report, evidence).header(HttpHeaders.AUTHORIZATION, bearer("analyst")))
                .andExpect(status().isOk()).andExpect(content().bytes(PNG));
        assertThat(auditsOf(report)).singleElement()
                .satisfies(row -> assertThat(row.getOutcome()).isEqualTo(EvidenceAccessAuditEvent.Outcome.SUCCEEDED));
    }

    @Test
    void stateChangedDuringTheStorageReadIsRecheckedBeforeTheSuccessAudit() throws Exception {
        UUID report = report(tenantA, eventA, LocalDateTime.now(ZoneOffset.UTC));
        UUID evidence = asset(report, "image/png", PNG, true);
        given(storage.readVerifiedBytes(eq(key(evidence)), any(), anyLong())).willAnswer(call -> {
            // S3를 읽는 동안 다른 작업이 저장 상태를 바꾼 상황입니다.
            jdbc.update("UPDATE evidence_assets SET object_version='replaced' WHERE id=?", evidence.toString());
            return bytes(PNG, "image/png");
        });
        expect(report, evidence, 409, "SOURCE_CHANGED");
        assertThat(auditsOf(report)).singleElement().satisfies(row -> {
            assertThat(row.getOutcome()).isEqualTo(EvidenceAccessAuditEvent.Outcome.FAILED);
            assertThat(row.getDetailCode()).isEqualTo("SOURCE_CHANGED");
        });
    }

    @Test
    void concurrentReadsAboveTheLimitAreRejectedImmediatelyAndAudited() throws Exception {
        UUID report = report(tenantA, eventA, LocalDateTime.now(ZoneOffset.UTC));
        UUID evidence = asset(report, "image/png", PNG, true);
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        given(storage.readVerifiedBytes(eq(key(evidence)), any(), anyLong())).willAnswer(call -> {
            reading.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return bytes(PNG, "image/png");
        });
        String token = bearer("analyst");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> first = executor.submit(() -> mvc.perform(read(report, evidence)
                    .header(HttpHeaders.AUTHORIZATION, token)).andReturn());
            assertThat(reading.await(10, TimeUnit.SECONDS)).isTrue();
            // 설정한 동시 읽기 수(테스트 값 1)를 넘으면 기다리지 않고 바로 503으로 거절합니다.
            expect(report, evidence, 503, "EVIDENCE_CONTENT_BUSY");
            release.countDown();
            MvcResult completed = first.get(10, TimeUnit.SECONDS);
            assertThat(completed.getResponse().getStatus()).isEqualTo(200);
            assertThat(completed.getResponse().getContentAsByteArray()).isEqualTo(PNG);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        mvc.perform(read(report, evidence).header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isOk());
        assertThat(auditsOf(report)).extracting(EvidenceAccessAuditEvent::getDetailCode)
                .containsExactlyInAnyOrder("VERIFIED_FOR_RESPONSE", "VERIFIED_FOR_RESPONSE", "EVIDENCE_CONTENT_BUSY");
    }

    private MvcResult expect(UUID report, UUID evidence, int status, String code) throws Exception {
        MvcResult result = mvc.perform(read(report, evidence).header(HttpHeaders.AUTHORIZATION, bearer("analyst")))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn();
        assertThat(result.getResponse().getContentType()).doesNotStartWith("image/");
        assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION)).isNull();
        return result;
    }

    private void verified(UUID evidence, byte[] bytes, String mediaType) {
        given(storage.readVerifiedBytes(eq(key(evidence)), eq("marker-" + evidence), anyLong()))
                .willReturn(bytes(bytes, mediaType));
    }

    private static EvidenceObjectStorage.VerifiedBytes bytes(byte[] bytes, String mediaType) {
        return new EvidenceObjectStorage.VerifiedBytes(new EvidenceObjectStorage.ByteVerification(bytes.length,
                mediaType, sha256(bytes), "marker", EvidenceObjectStorage.MarkerKind.ETAG), bytes);
    }

    private String key(UUID evidence) {
        return assets.findById(evidence).orElseThrow().getObjectKey();
    }

    private List<EvidenceAccessAuditEvent> auditsOf(UUID report) {
        return audits.findAll().stream().filter(row -> report.equals(row.getFanReportId())).toList();
    }

    private static List<String> headerValues(MvcResult result) {
        return result.getResponse().getHeaderNames().stream()
                .flatMap(name -> result.getResponse().getHeaders(name).stream()).toList();
    }

    private void member(UUID tenant, UUID event, String subject, AdminRole role) {
        UUID membership = UUID.randomUUID();
        memberships.saveAndFlush(new AdminMembershipEntity(membership, tenant, subject + "-" + run,
                AccessRecordStatus.ACTIVE));
        if (role != null) roles.saveAndFlush(new MembershipRole(UUID.randomUUID(), membership, role));
        grants.saveAndFlush(new MembershipEventGrant(UUID.randomUUID(), membership, tenant, event));
    }

    private UUID report(UUID tenant, UUID event, LocalDateTime receivedAt) {
        return reportWithStatus(tenant, event, receivedAt, "RECEIVED");
    }

    private UUID reportWithStatus(UUID tenant, UUID event, LocalDateTime receivedAt, String status) {
        UUID id = UUID.randomUUID();
        FanReport report = new FanReport(id, "FR-" + id, tenant, event, receivedAt.plusDays(20), null,
                FanReport.ReportType.PREMIUM_RESALE, "합성 제보", null, null, null, new BigDecimal("1000.00"),
                receivedAt, "idem-" + id, "d".repeat(64), "e".repeat(64));
        if ("RECEIVED".equals(status)) report.markReceived();
        if ("FAILED".equals(status)) report.markStorageFailed();
        reports.saveAndFlush(report);
        return id;
    }

    private UUID asset(UUID report, String mediaType, byte[] bytes, boolean stored) {
        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        EvidenceAsset asset = new EvidenceAsset(id, report, "fan-reports/" + report + "/" + id, mediaType,
                bytes.length, sha256(bytes), now, now.plusDays(90));
        if (stored) asset.markStored("marker-" + id);
        assets.saveAndFlush(asset);
        return id;
    }

    private static MockHttpServletRequestBuilder read(UUID report, UUID evidence) {
        return get("/api/v1/admin/fan-reports/{reportId}/evidence/{evidenceId}/content", report, evidence);
    }

    private String bearer(String subject) throws Exception {
        Instant now = Instant.now();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), new JWTClaimsSet.Builder()
                .subject(subject + "-" + run).issuer(ADMIN_ISSUER).audience(ADMIN_AUDIENCE)
                .claim("tenantId", tenantB.toString())
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300))).build());
        jwt.sign(new RSASSASigner(keyPair.getPrivate()));
        return "Bearer " + jwt.serialize();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
