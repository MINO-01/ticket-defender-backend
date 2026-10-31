package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.out.persistence.*;
import com.ticket.defender_core.application.service.CaseApiException;
import com.ticket.defender_core.application.service.EvidenceObjectStorage;
import com.ticket.defender_core.application.service.ListingContextAnalysisService;
import com.ticket.defender_core.domain.*;
import com.ticket.defender_core.security.AdminRole;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "ticket-defender.track2-listing-analysis.enabled=true",
        "ticket-defender.track2-listing-analysis.internal-key=" + FakeListingContextServer.INTERNAL_KEY,
        "ticket-defender.track2-listing-analysis.read-timeout=PT10S"
})
@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MYSQL_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:13307/td_issue32.*")
class Track2ListingAnalysisConcurrentMySqlTest {
    private static final FakeListingContextServer PYTHON = start();
    private static final byte[] IMAGE = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0};

    @Autowired private ListingContextAnalysisService service;
    @Autowired private TenantRepository tenants;
    @Autowired private TenantEventRepository events;
    @Autowired private AdminMembershipRepository memberships;
    @Autowired private MembershipRoleRepository roles;
    @Autowired private MembershipEventGrantRepository grants;
    @Autowired private FanReportRepository reports;
    @Autowired private EvidenceAssetRepository assets;
    @Autowired private ListingContextAnalysisRepository analyses;
    @MockitoBean private EvidenceObjectStorage storage;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TD_ISSUE32_MYSQL_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("TD_ISSUE32_MYSQL_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("TD_ISSUE32_MYSQL_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("ticket-defender.track2-listing-analysis.base-url", PYTHON::baseUrl);
    }

    @AfterAll
    static void stop() {
        PYTHON.close();
    }

    @Test
    void distinctKeysForSameEvidenceAllowOnlyOneActiveAnalysis() throws Exception {
        Fixture f = fixture();
        List<String> outcomes = race(f, UUID.randomUUID().toString(), UUID.randomUUID().toString());
        assertThat(outcomes).filteredOn(item -> item.startsWith("OK:")).hasSize(1);
        assertThat(outcomes).filteredOn("ERR:ANALYSIS_IN_PROGRESS"::equals).hasSize(1);
        assertThat(rows(f)).hasSize(1).first().satisfies(row -> {
            assertThat(row.getExecutionStatus()).isEqualTo(ListingContextAnalysis.Execution.SUCCEEDED);
            assertThat(row.getActiveEvidenceId()).isNull();
        });
    }

    @Test
    void sameKeyRaceCallsAnalyzerOnceAndReplaysTheCommittedResult() throws Exception {
        Fixture f = fixture();
        String key = UUID.randomUUID().toString();
        List<String> outcomes = race(f, key, key);
        assertThat(outcomes).filteredOn(item -> item.startsWith("OK:")).hasSize(1);
        assertThat(outcomes).filteredOn("ERR:ANALYSIS_IN_PROGRESS"::equals).hasSize(1);
        String committed = outcomes.stream().filter(item -> item.startsWith("OK:")).findFirst().orElseThrow();
        authenticate(f.subject());
        try {
            assertThat("OK:" + service.analyze(f.reportId(), f.evidenceId(), key).analysisId()).isEqualTo(committed);
        } finally {
            SecurityContextHolder.clearContext();
        }
        assertThat(rows(f)).hasSize(1);
    }

    private List<String> race(Fixture f, String firstKey, String secondKey) throws Exception {
        PYTHON.received.clear();
        CountDownLatch release = new CountDownLatch(1);
        PYTHON.responder = body -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return FakeListingContextServer.success(body, "COMPLETED", FakeListingContextServer.completedFields());
        };
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (String key : List.of(firstKey, secondKey)) futures.add(pool.submit(() -> {
                authenticate(f.subject());
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                    return "OK:" + service.analyze(f.reportId(), f.evidenceId(), key).analysisId();
                } catch (CaseApiException rejected) {
                    return "ERR:" + rejected.code();
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (futures.stream().noneMatch(Future::isDone) && System.nanoTime() < deadline) Thread.onSpinWait();
            release.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> future : futures) outcomes.add(future.get(15, TimeUnit.SECONDS));
            assertThat(PYTHON.received).hasSize(1);
            return outcomes;
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private Fixture fixture() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        String subject = "listing-mysql-analyst-" + member;
        tenants.saveAndFlush(new Tenant(tenant, "Synthetic listing tenant", AccessRecordStatus.ACTIVE));
        events.saveAndFlush(new TenantEvent(event, tenant, "synthetic-" + event, "Synthetic show",
                AccessRecordStatus.ACTIVE));
        memberships.saveAndFlush(new AdminMembershipEntity(member, tenant, subject, AccessRecordStatus.ACTIVE));
        roles.saveAndFlush(new MembershipRole(UUID.randomUUID(), member, AdminRole.ANALYST));
        grants.saveAndFlush(new MembershipEventGrant(UUID.randomUUID(), member, tenant, event));
        UUID reportId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        FanReport report = new FanReport(reportId, "FR-" + reportId, tenant, event, now.plusDays(1), null,
                FanReport.ReportType.PREMIUM_RESALE, "Synthetic listing report", null, null, null, null, now,
                UUID.randomUUID().toString(), "d".repeat(64), "e".repeat(64));
        report.markReceived();
        reports.saveAndFlush(report);
        UUID evidenceId = UUID.randomUUID();
        String key = "fan-reports/" + reportId + "/" + evidenceId;
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(IMAGE));
        EvidenceAsset asset = new EvidenceAsset(evidenceId, reportId, key, "image/png", IMAGE.length, digest, now,
                report.getRetentionUntil());
        asset.markStored("etag");
        assets.saveAndFlush(asset);
        when(storage.readVerifiedBytes(key, "etag", 5L * 1024 * 1024)).thenReturn(
                new EvidenceObjectStorage.VerifiedBytes(new EvidenceObjectStorage.ByteVerification(IMAGE.length,
                        "image/png", digest, "etag", EvidenceObjectStorage.MarkerKind.ETAG), IMAGE.clone()));
        return new Fixture(reportId, evidenceId, subject);
    }

    private List<ListingContextAnalysis> rows(Fixture f) {
        return analyses.findByFanReportIdAndEvidenceId(f.reportId(), f.evidenceId(), Pageable.unpaged()).getContent();
    }

    private static void authenticate(String subject) {
        JwtAuthenticationToken token = new JwtAuthenticationToken(
                Jwt.withTokenValue("synthetic").header("alg", "RS256").subject(subject).build());
        token.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private static FakeListingContextServer start() {
        try {
            return new FakeListingContextServer();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private record Fixture(UUID reportId, UUID evidenceId, String subject) { }
}
