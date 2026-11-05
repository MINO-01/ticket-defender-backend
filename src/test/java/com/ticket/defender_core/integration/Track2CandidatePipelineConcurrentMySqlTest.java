package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.in.web.dto.CandidatePipelineDto;
import com.ticket.defender_core.adapter.out.persistence.*;
import com.ticket.defender_core.application.service.CaseApiException;
import com.ticket.defender_core.application.service.ListingCandidateMatchService;
import com.ticket.defender_core.application.service.ListingCandidateMatchWriteService;
import com.ticket.defender_core.domain.*;
import com.ticket.defender_core.security.AdminRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.datasource.hikari.connection-init-sql=SET SESSION lock_wait_timeout = 2, innodb_lock_wait_timeout = 2"
})
@EnabledIfEnvironmentVariable(named = "TD_ISSUE32_MYSQL_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:13307/td_issue32.*")
class Track2CandidatePipelineConcurrentMySqlTest {
    @Autowired private ListingCandidateMatchService service;
    @Autowired private TenantRepository tenants;
    @Autowired private TenantEventRepository events;
    @Autowired private AdminMembershipRepository memberships;
    @Autowired private MembershipRoleRepository roles;
    @Autowired private MembershipEventGrantRepository grants;
    @Autowired private FanReportRepository reports;
    @Autowired private EvidenceAssetRepository assets;
    @Autowired private ListingContextAnalysisRepository analyses;
    @Autowired private ListingContextObservationRepository observations;
    @Autowired private VerifiedPerformanceRepository performances;
    @Autowired private FanReportPerformanceLinkRepository links;
    @Autowired private ReservationImportBatchRepository batches;
    @Autowired private ReservationDataSourceRepository sources;
    @Autowired private ReservationCandidateRepository candidates;
    @Autowired private ListingCandidateMatchRunRepository runs;
    @MockitoSpyBean private ListingCandidateMatchWriteService writer;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TD_ISSUE32_MYSQL_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("TD_ISSUE32_MYSQL_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("TD_ISSUE32_MYSQL_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @AfterEach
    void resetCandidateMatchWriteServiceSpy() {
        reset(writer);
        SecurityContextHolder.clearContext();
    }

    @Test
    void distinctConcurrentKeysLeaveOnlyOneActiveCandidateExecution() throws Exception {
        Fixture fixture = fixture();
        List<String> outcomes = race(fixture, UUID.randomUUID().toString(), UUID.randomUUID().toString());

        assertThat(outcomes).filteredOn(item -> item.startsWith("OK:")).hasSize(1);
        assertThat(outcomes).filteredOn("ERR:CANDIDATE_LOOKUP_IN_PROGRESS"::equals).hasSize(1);
        assertThat(runs.findByAnalysisId(fixture.analysisId(), org.springframework.data.domain.Pageable.unpaged())
                .getContent()).hasSize(1).allSatisfy(run -> {
                    assertThat(run.inProgress()).isFalse();
                    assertThat(run.getStatus()).isEqualTo(com.ticket.defender_core.application.service.CandidateMatchDecision.Status.MATCH_CANDIDATE);
                });
    }

    @Test
    void sameConcurrentKeyCanBeRetriedAsTheCommittedImmutableRun() throws Exception {
        Fixture fixture = fixture();
        String key = UUID.randomUUID().toString();
        List<String> outcomes = race(fixture, key, key);

        assertThat(outcomes).filteredOn(item -> item.startsWith("OK:")).hasSize(1);
        assertThat(outcomes).filteredOn("ERR:CANDIDATE_LOOKUP_IN_PROGRESS"::equals).hasSize(1);
        String committed = outcomes.stream().filter(item -> item.startsWith("OK:")).findFirst().orElseThrow();
        authenticate(fixture.subject());
        try {
            CandidatePipelineDto.CandidateRun replay = service.match(fixture.reportId(), fixture.evidenceId(),
                    fixture.analysisId(), key);
            assertThat("OK:" + replay.runId()).isEqualTo(committed);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void expiredActiveRunIsClosedOnceAndLateCompletionCannotOverwriteIt() throws Exception {
        Fixture fixture = fixture();
        ListingCandidateMatchRun expired = runs.saveAndFlush(expiredRun(fixture));

        List<String> outcomes = race(fixture, UUID.randomUUID().toString(), UUID.randomUUID().toString());

        assertThat(outcomes).filteredOn(item -> item.startsWith("OK:")).hasSize(1);
        assertThat(outcomes).filteredOn("ERR:CANDIDATE_LOOKUP_IN_PROGRESS"::equals).hasSize(1);
        List<ListingCandidateMatchRun> history = runs.findByAnalysisId(fixture.analysisId(),
                org.springframework.data.domain.Pageable.unpaged()).getContent();
        assertThat(history).hasSize(2);
        assertThat(history).filteredOn(run -> run.getId().equals(expired.getId())).singleElement().satisfies(run -> {
            assertThat(run.inProgress()).isFalse();
            assertThat(run.getStatus()).isEqualTo(com.ticket.defender_core.application.service.CandidateMatchDecision.Status.LOOKUP_FAILED);
            assertThat(run.getReason()).isEqualTo(com.ticket.defender_core.application.service.CandidateMatchDecision.Reason.RESERVATION_LOOKUP_FAILED);
        });

        ListingCandidateMatchRun late = writer.complete(expired.getId(),
                new com.ticket.defender_core.application.service.CandidateMatchDecision.Result(
                        com.ticket.defender_core.application.service.CandidateMatchDecision.Status.MATCH_CANDIDATE,
                        com.ticket.defender_core.application.service.CandidateMatchDecision.Reason.MATCHED_SINGLE_CANDIDATE),
                1, UUID.randomUUID());
        assertThat(late.getStatus()).isEqualTo(com.ticket.defender_core.application.service.CandidateMatchDecision.Status.LOOKUP_FAILED);
        assertThat(late.getCandidateId()).isNull();
    }

    @Test
    void lateCompletionAfterLeaseExpiryIsRecordedAsLookupFailureBeforeAnyRetry() throws Exception {
        Fixture fixture = fixture();
        ListingCandidateMatchRun expired = runs.saveAndFlush(expiredRun(fixture));

        ListingCandidateMatchRun late = writer.complete(expired.getId(),
                new com.ticket.defender_core.application.service.CandidateMatchDecision.Result(
                        com.ticket.defender_core.application.service.CandidateMatchDecision.Status.MATCH_CANDIDATE,
                        com.ticket.defender_core.application.service.CandidateMatchDecision.Reason.MATCHED_SINGLE_CANDIDATE),
                1, UUID.randomUUID());

        assertThat(late.inProgress()).isFalse();
        assertThat(late.getStatus()).isEqualTo(com.ticket.defender_core.application.service.CandidateMatchDecision.Status.LOOKUP_FAILED);
        assertThat(late.getReason()).isEqualTo(com.ticket.defender_core.application.service.CandidateMatchDecision.Reason.RESERVATION_LOOKUP_FAILED);
        assertThat(late.getCandidateId()).isNull();
    }

    private List<String> race(Fixture fixture, String firstKey, String secondKey) throws Exception {
        CountDownLatch completionEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // The service reaches complete only after the real Spring Data candidate lookup returned.
        doAnswer(invocation -> {
            completionEntered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return invocation.callRealMethod();
        }).when(writer).complete(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (String key : List.of(firstKey, secondKey)) futures.add(pool.submit(() -> {
                authenticate(fixture.subject());
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                    return "OK:" + service.match(fixture.reportId(), fixture.evidenceId(), fixture.analysisId(), key)
                            .runId();
                } catch (CaseApiException rejected) {
                    return "ERR:" + rejected.code();
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }));
            assertThat(completionEntered.await(10, TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (futures.stream().noneMatch(Future::isDone) && System.nanoTime() < deadline) Thread.onSpinWait();
            release.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> future : futures) outcomes.add(future.get(15, TimeUnit.SECONDS));
            return outcomes;
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private ListingCandidateMatchRun expiredRun(Fixture fixture) {
        FanReport report = reports.findById(fixture.reportId()).orElseThrow();
        ListingContextAnalysis analysis = analyses.findById(fixture.analysisId()).orElseThrow();
        EvidenceAsset asset = assets.findById(fixture.evidenceId()).orElseThrow();
        FanReportPerformanceLink link = links.findByActiveFanReportId(fixture.reportId()).orElseThrow();
        VerifiedPerformance performance = performances.findById(link.getPerformanceId()).orElseThrow();
        ReservationDataSource source = sources.findByTenantIdAndEventIdAndPerformanceId(report.getTenantId(),
                report.getEventId(), performance.getId()).orElseThrow();
        UUID member = memberships.findBySubjectAndTenantId(fixture.subject(), report.getTenantId()).orElseThrow().getId();
        ListingCandidateMatchRun.Binding binding = new ListingCandidateMatchRun.Binding(link.getId(),
                link.getLinkRevision(), performance.getId(), performance.getRevision(), source.getId(),
                source.getSourceRevision(), source.getCurrentBatchId(), source.getCurrentSourceVersion(), asset.getSha256(),
                analysis.getResponseSha256(), analysis.getRowVersion());
        LocalDateTime startedAt = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(10);
        return new ListingCandidateMatchRun(report.getTenantId(), report.getEventId(), fixture.reportId(),
                fixture.evidenceId(), fixture.analysisId(), member, "a".repeat(64), "b".repeat(64), binding,
                startedAt, startedAt.plusMinutes(1));
    }

    private Fixture fixture() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        String subject = "candidate-mysql-" + member;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        tenants.saveAndFlush(new Tenant(tenant, "Synthetic candidate tenant", AccessRecordStatus.ACTIVE));
        events.saveAndFlush(new TenantEvent(event, tenant, "candidate-" + event, "Synthetic candidate event",
                AccessRecordStatus.ACTIVE));
        memberships.saveAndFlush(new AdminMembershipEntity(member, tenant, subject, AccessRecordStatus.ACTIVE));
        roles.saveAndFlush(new MembershipRole(UUID.randomUUID(), member, AdminRole.ANALYST));
        grants.saveAndFlush(new MembershipEventGrant(UUID.randomUUID(), member, tenant, event));

        UUID reportId = UUID.randomUUID();
        String receiptNumber = "FR-CAND-" + reportId.toString().replace("-", "");
        assertThat(receiptNumber).hasSize(40);
        FanReport report = new FanReport(reportId, receiptNumber, tenant, event, now.plusDays(1), null,
                FanReport.ReportType.PREMIUM_RESALE, "Synthetic listing report", null, null, null, null, now,
                UUID.randomUUID().toString(), "a".repeat(64), "b".repeat(64));
        report.markReceived();
        reports.saveAndFlush(report);
        UUID evidenceId = UUID.randomUUID();
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new byte[] {1, 2, 3, 4}));
        EvidenceAsset asset = new EvidenceAsset(evidenceId, reportId, "synthetic/" + evidenceId, "image/png", 4,
                sha, now, now.plusDays(90));
        asset.markStored("candidate-etag");
        assets.saveAndFlush(asset);
        UUID analysisId = UUID.randomUUID();
        ListingContextAnalysis analysis = new ListingContextAnalysis(analysisId, tenant, event, reportId, evidenceId,
                member, "c".repeat(64), UUID.randomUUID(), "d".repeat(64), "candidate-etag", sha, 4, "image/png",
                now, now.plusMinutes(5));
        analysis.recordVerifiedInput(sha, 4);
        analysis.succeed(ListingContextAnalysis.AnalysisStatus.COMPLETED, "synthetic", "v1", now,
                "e".repeat(64), "MATCH_SCORE_UNAVAILABLE", now);
        analyses.saveAndFlush(analysis);
        for (ListingContextObservation.Field field : List.of(ListingContextObservation.Field.zone,
                ListingContextObservation.Field.row, ListingContextObservation.Field.seat)) {
            String value = switch (field) {
                case zone -> "VIP";
                case row -> "10";
                case seat -> "5";
                default -> throw new IllegalStateException();
            };
            observations.saveAndFlush(new ListingContextObservation(analysisId, field,
                    ListingContextObservation.State.FOUND, value, null, null, value, null, List.of()));
        }
        VerifiedPerformance performance = performances.saveAndFlush(new VerifiedPerformance(UUID.randomUUID(), tenant,
                event, "round-" + event, "synthetic verified", "performance-v1", member, now));
        links.saveAndFlush(new FanReportPerformanceLink(tenant, event, reportId, performance, 1, "synthetic link",
                member, now));
        ReservationImportBatch batch = batches.saveAndFlush(new ReservationImportBatch(tenant, event, performance,
                member, "f".repeat(64), "1".repeat(64), "source-v1", "v1", UUID.randomUUID(), true,
                ReservationImportBatch.Status.ACCEPTED, 1, 1, 0, now));
        candidates.saveAndFlush(new ReservationCandidate(batch.getId(), 0, "2".repeat(64), "v1", "VIP", "10", "5"));
        sources.saveAndFlush(new ReservationDataSource(tenant, event, performance.getId(), batch, now));
        return new Fixture(reportId, evidenceId, analysisId, subject);
    }

    private static void authenticate(String subject) {
        JwtAuthenticationToken token = new JwtAuthenticationToken(
                Jwt.withTokenValue("synthetic").header("alg", "RS256").subject(subject).build());
        token.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private record Fixture(UUID reportId, UUID evidenceId, UUID analysisId, String subject) { }
}
