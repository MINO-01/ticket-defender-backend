package com.ticket.defender_core.integration;

import com.ticket.defender_core.adapter.out.persistence.AdminMembershipRepository;
import com.ticket.defender_core.adapter.out.persistence.EvidenceAssetRepository;
import com.ticket.defender_core.adapter.out.persistence.FanReportRepository;
import com.ticket.defender_core.adapter.out.persistence.ListingContextAnalysisRepository;
import com.ticket.defender_core.adapter.out.persistence.ListingContextObservationRepository;
import com.ticket.defender_core.adapter.out.persistence.MembershipEventGrantRepository;
import com.ticket.defender_core.adapter.out.persistence.MembershipRoleRepository;
import com.ticket.defender_core.adapter.out.persistence.SyntheticReservationImportTenantRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantEventRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantRepository;
import com.ticket.defender_core.domain.AccessRecordStatus;
import com.ticket.defender_core.domain.AdminMembershipEntity;
import com.ticket.defender_core.domain.EvidenceAsset;
import com.ticket.defender_core.domain.FanReport;
import com.ticket.defender_core.domain.ListingContextAnalysis;
import com.ticket.defender_core.domain.ListingContextObservation;
import com.ticket.defender_core.domain.MembershipEventGrant;
import com.ticket.defender_core.domain.MembershipRole;
import com.ticket.defender_core.domain.SyntheticReservationImportTenant;
import com.ticket.defender_core.domain.Tenant;
import com.ticket.defender_core.domain.TenantEvent;
import com.ticket.defender_core.security.AdminRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Lightweight HTTP regression coverage; the matching MySQL 8 HTTP test remains separately isolated. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:track2-candidate-pipeline;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false",
        "ticket-defender.track2-candidate-import.enabled=true",
        "ticket-defender.track2-candidate-import.operational-mode=SYNTHETIC_ISOLATED",
        "ticket-defender.track2-candidate-import.synthetic-isolation-id=" + Track2CandidatePipelineIntegrationTest.ISOLATION_ID,
        "ticket-defender.track2-candidate-import.reference-hmac-master-key=synthetic-only-master-key-material-that-is-long-enough",
        "ticket-defender.track2-candidate-import.reference-hmac-key-version=v1"
})
@AutoConfigureMockMvc
class Track2CandidatePipelineIntegrationTest {
    static final String ISOLATION_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private static final Pattern ID = Pattern.compile("\\\"(?:performanceId|batchId|runId)\\\":\\\"([^\\\"]+)\\\"");

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
    @Autowired private SyntheticReservationImportTenantRepository syntheticTenants;
    @MockitoBean(name = "adminJwtDecoder") private JwtDecoder decoder;

    @BeforeEach
    void decoderUsesTheSyntheticBearerValueAsSubject() {
        given(decoder.decode(anyString())).willAnswer(call -> Jwt.withTokenValue(call.getArgument(0))
                .header("alg", "RS256").subject(call.getArgument(0)).build());
    }

    @Test
    void immutableRunBindsProvenanceAndReevaluatesApplicabilityAfterPerformanceChanges() throws Exception {
        Fixture fixture = fixture(ListingContextObservation.State.FOUND, ListingContextObservation.State.FOUND,
                ListingContextObservation.State.FOUND);
        String performanceId = createPerformance(fixture);
        link(fixture, performanceId, 0);
        importRows(fixture, performanceId, "source-v1", true, "VIP", "10", "5");
        String key = UUID.randomUUID().toString();

        MvcResult first = candidate(fixture, key).andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("MATCH_CANDIDATE"))
                .andExpect(jsonPath("$.reason").value("MATCHED_SINGLE_CANDIDATE"))
                .andExpect(jsonPath("$.applicability").value("CURRENT"))
                .andExpect(jsonPath("$.evidenceSha256").value(fixture.sha256()))
                .andReturn();
        String runId = id(first.getResponse().getContentAsString());
        candidate(fixture, key).andExpect(status().isCreated()).andExpect(jsonPath("$.runId").value(runId));

        mvc.perform(patch("/api/v1/admin/events/{eventId}/verified-performances/{performanceId}", fixture.eventId(), performanceId)
                        .header("Authorization", bearer(fixture.subject()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verificationBasis\":\"synthetic revised\",\"sourceVersion\":\"performance-v2\",\"expectedRevision\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2));
        history(fixture).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].runId").value(runId))
                .andExpect(jsonPath("$.items[0].status").value("MATCH_CANDIDATE"))
                .andExpect(jsonPath("$.items[0].applicability").value("PERFORMANCE_REVISION_CHANGED"));

        mvc.perform(post("/api/v1/admin/events/{eventId}/verified-performances/{performanceId}/withdrawals",
                        fixture.eventId(), performanceId).header("Authorization", bearer(fixture.subject()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"synthetic withdrawal\",\"expectedRevision\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("WITHDRAWN"));
        history(fixture).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("MATCH_CANDIDATE"))
                .andExpect(jsonPath("$.items[0].applicability").value("PERFORMANCE_WITHDRAWN"));

        String replacementPerformance = createPerformance(fixture, "replacement");
        link(fixture, replacementPerformance, 1);
        history(fixture).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("MATCH_CANDIDATE"))
                .andExpect(jsonPath("$.items[0].applicability").value("PERFORMANCE_LINK_REPLACED_OR_WITHDRAWN"));
        mvc.perform(post("/api/v1/admin/fan-reports/{fanReportId}/performance-link/withdrawals", fixture.reportId())
                        .header("Authorization", bearer(fixture.subject())).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedLinkRevision\":2,\"reason\":\"synthetic link withdrawal\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("WITHDRAWN"));
        history(fixture).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("MATCH_CANDIDATE"))
                .andExpect(jsonPath("$.items[0].applicability").value("PERFORMANCE_LINK_REPLACED_OR_WITHDRAWN"));
    }

    @Test
    void completeSnapshotAndExactFoundObservationsControlNoMatchAmbiguityAndUnverifiableResults() throws Exception {
        Fixture noMatch = fixture(ListingContextObservation.State.FOUND, ListingContextObservation.State.FOUND,
                ListingContextObservation.State.FOUND);
        String noMatchPerformance = createPerformance(noMatch);
        link(noMatch, noMatchPerformance, 0);
        importRows(noMatch, noMatchPerformance, "source-v1", true, "VIP", "10", "99");
        candidate(noMatch, UUID.randomUUID().toString()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("NO_MATCH"))
                .andExpect(jsonPath("$.reason").value("NO_CANDIDATE_FOUND"));

        Fixture ambiguous = fixture(ListingContextObservation.State.FOUND, ListingContextObservation.State.FOUND,
                ListingContextObservation.State.FOUND);
        String ambiguousPerformance = createPerformance(ambiguous);
        link(ambiguous, ambiguousPerformance, 0);
        importRows(ambiguous, ambiguousPerformance, "source-v1", true, "VIP", "10", "5", "VIP", "10", "5");
        candidate(ambiguous, UUID.randomUUID().toString()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AMBIGUOUS"))
                .andExpect(jsonPath("$.reason").value("MULTIPLE_CANDIDATES"))
                .andExpect(jsonPath("$.candidateId").doesNotExist());

        Fixture unknownSeat = fixture(ListingContextObservation.State.FOUND, ListingContextObservation.State.FOUND,
                ListingContextObservation.State.UNKNOWN);
        String unknownPerformance = createPerformance(unknownSeat);
        link(unknownSeat, unknownPerformance, 0);
        importRows(unknownSeat, unknownPerformance, "source-v1", true, "VIP", "10", "5");
        candidate(unknownSeat, UUID.randomUUID().toString()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("UNVERIFIABLE"))
                .andExpect(jsonPath("$.reason").value("SEAT_OBSERVATION_UNAVAILABLE"));

        Fixture ambiguousSeat = fixture(ListingContextObservation.State.FOUND, ListingContextObservation.State.FOUND,
                ListingContextObservation.State.AMBIGUOUS);
        String ambiguousSeatPerformance = createPerformance(ambiguousSeat);
        link(ambiguousSeat, ambiguousSeatPerformance, 0);
        importRows(ambiguousSeat, ambiguousSeatPerformance, "source-v1", true, "VIP", "10", "5");
        candidate(ambiguousSeat, UUID.randomUUID().toString()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("UNVERIFIABLE"))
                .andExpect(jsonPath("$.reason").value("SEAT_OBSERVATION_AMBIGUOUS"));
    }

    @Test
    void importRetriesAreIdempotentAndCrossTenantCandidateReadIsHidden() throws Exception {
        Fixture fixture = fixture(ListingContextObservation.State.FOUND, ListingContextObservation.State.FOUND,
                ListingContextObservation.State.FOUND);
        String performanceId = createPerformance(fixture);
        link(fixture, performanceId, 0);
        String key = UUID.randomUUID().toString();
        String body = importBody(performanceId, "source-v1", true, "VIP", "10", "5");
        MvcResult imported = mvc.perform(post("/api/v1/admin/events/{eventId}/reservation-imports", fixture.eventId())
                        .header("Authorization", bearer(fixture.subject())).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ACCEPTED")).andReturn();
        String batchId = id(imported.getResponse().getContentAsString());
        mvc.perform(post("/api/v1/admin/events/{eventId}/reservation-imports", fixture.eventId())
                        .header("Authorization", bearer(fixture.subject())).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.batchId").value(batchId));
        mvc.perform(post("/api/v1/admin/events/{eventId}/reservation-imports", fixture.eventId())
                        .header("Authorization", bearer(fixture.subject())).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importBody(performanceId, "source-v1", true, "VIP", "10", "6")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        String outsider = outsiderMember(UUID.randomUUID(), UUID.randomUUID(), "candidate-outsider-" + UUID.randomUUID(),
                AdminRole.REVIEWER);
        mvc.perform(get(candidatePath(fixture)).header("Authorization", bearer(outsider)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        Fixture incomplete = fixture(ListingContextObservation.State.FOUND, ListingContextObservation.State.FOUND,
                ListingContextObservation.State.FOUND);
        String incompletePerformance = createPerformance(incomplete);
        link(incomplete, incompletePerformance, 0);
        mvc.perform(post("/api/v1/admin/events/{eventId}/reservation-imports", incomplete.eventId())
                        .header("Authorization", bearer(incomplete.subject()))
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content(importBody(incompletePerformance, "source-v1", false, "VIP", "10", "5")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.errors[0].code").value("INCOMPLETE_SOURCE"));
        candidate(incomplete, UUID.randomUUID().toString()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("UNVERIFIABLE"))
                .andExpect(jsonPath("$.reason").value("RESERVATION_SOURCE_UNAVAILABLE"));
    }

    private Fixture fixture(ListingContextObservation.State zoneState, ListingContextObservation.State rowState,
            ListingContextObservation.State seatState) throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        String subject = "candidate-user-" + UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        tenants.saveAndFlush(new Tenant(tenant, "Synthetic candidate tenant", AccessRecordStatus.ACTIVE));
        events.saveAndFlush(new TenantEvent(event, tenant, "event-" + event, "Synthetic candidate event",
                AccessRecordStatus.ACTIVE));
        existingMember(tenant, event, subject, AdminRole.TENANT_ADMIN, AdminRole.REVIEWER, AdminRole.ANALYST);
        syntheticTenants.saveAndFlush(new SyntheticReservationImportTenant(tenant, UUID.fromString(ISOLATION_ID),
                now.minusMinutes(1)));
        UUID reportId = UUID.randomUUID();
        FanReport report = new FanReport(reportId, "FR-" + reportId, tenant, event, now.plusDays(1), null,
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
                memberships.findBySubjectAndTenantId(subject, tenant).orElseThrow().getId(), "c".repeat(64),
                UUID.randomUUID(), "d".repeat(64), "candidate-etag", sha, 4, "image/png", now,
                now.plusMinutes(5));
        analysis.recordVerifiedInput(sha, 4);
        analysis.succeed(ListingContextAnalysis.AnalysisStatus.COMPLETED, "synthetic", "v1", now,
                "e".repeat(64), "MATCH_SCORE_UNAVAILABLE", now);
        analyses.saveAndFlush(analysis);
        observation(analysisId, ListingContextObservation.Field.zone, zoneState, "VIP");
        observation(analysisId, ListingContextObservation.Field.row, rowState, "10");
        observation(analysisId, ListingContextObservation.Field.seat, seatState, "5");
        return new Fixture(tenant, event, reportId, evidenceId, analysisId, subject, sha);
    }

    private void observation(UUID analysisId, ListingContextObservation.Field field,
            ListingContextObservation.State state, String value) {
        boolean found = state == ListingContextObservation.State.FOUND;
        boolean ambiguous = state == ListingContextObservation.State.AMBIGUOUS;
        observations.saveAndFlush(new ListingContextObservation(analysisId, field, state, found ? value : null,
                null, null, found || ambiguous ? value : null,
                state == ListingContextObservation.State.UNKNOWN ? ListingContextObservation.UnknownReason.UNREADABLE : null,
                ambiguous ? List.of(value, value + "-alternative") : List.of()));
    }

    private String createPerformance(Fixture fixture) throws Exception {
        return createPerformance(fixture, "initial");
    }

    private String createPerformance(Fixture fixture, String suffix) throws Exception {
        MvcResult response = mvc.perform(post("/api/v1/admin/events/{eventId}/verified-performances", fixture.eventId())
                        .header("Authorization", bearer(fixture.subject())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"performanceRef\":\"round-" + suffix + "-" + fixture.reportId()
                                + "\",\"verificationBasis\":\"synthetic verified\",\"sourceVersion\":\"performance-v1\"}"))
                .andExpect(status().isCreated()).andReturn();
        return id(response.getResponse().getContentAsString());
    }

    private void link(Fixture fixture, String performanceId, int expectedRevision) throws Exception {
        mvc.perform(put("/api/v1/admin/fan-reports/{fanReportId}/performance-link", fixture.reportId())
                        .header("Authorization", bearer(fixture.subject()))
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"performanceId\":\"" + performanceId + "\",\"expectedLinkRevision\":"
                                + expectedRevision + ",\"reason\":\"synthetic link\"}"))
                .andExpect(status().isCreated());
    }

    private void importRows(Fixture fixture, String performanceId, String sourceVersion, boolean complete,
            String... seatCells) throws Exception {
        mvc.perform(post("/api/v1/admin/events/{eventId}/reservation-imports", fixture.eventId())
                        .header("Authorization", bearer(fixture.subject()))
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content(importBody(performanceId, sourceVersion, complete, seatCells)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    private org.springframework.test.web.servlet.ResultActions candidate(Fixture fixture, String key) throws Exception {
        return mvc.perform(post(candidatePath(fixture)).header("Authorization", bearer(fixture.subject()))
                .header("Idempotency-Key", key));
    }

    private org.springframework.test.web.servlet.ResultActions history(Fixture fixture) throws Exception {
        return mvc.perform(get(candidatePath(fixture)).header("Authorization", bearer(fixture.subject())));
    }

    private static String candidatePath(Fixture fixture) {
        return "/api/v1/admin/fan-reports/" + fixture.reportId() + "/evidence/" + fixture.evidenceId()
                + "/listing-analyses/" + fixture.analysisId() + "/candidate-matches";
    }

    private String outsiderMember(UUID tenant, UUID event, String subject, AdminRole... memberRoles) {
        UUID member = UUID.randomUUID();
        tenants.saveAndFlush(new Tenant(tenant, "Outsider tenant", AccessRecordStatus.ACTIVE));
        events.saveAndFlush(new TenantEvent(event, tenant, "event-" + event, "Outsider event", AccessRecordStatus.ACTIVE));
        memberships.saveAndFlush(new AdminMembershipEntity(member, tenant, subject, AccessRecordStatus.ACTIVE));
        for (AdminRole role : memberRoles) roles.saveAndFlush(new MembershipRole(UUID.randomUUID(), member, role));
        grants.saveAndFlush(new MembershipEventGrant(UUID.randomUUID(), member, tenant, event));
        return subject;
    }

    private String existingMember(UUID tenant, UUID event, String subject, AdminRole... memberRoles) {
        UUID member = UUID.randomUUID();
        memberships.saveAndFlush(new AdminMembershipEntity(member, tenant, subject, AccessRecordStatus.ACTIVE));
        for (AdminRole role : memberRoles) roles.saveAndFlush(new MembershipRole(UUID.randomUUID(), member, role));
        grants.saveAndFlush(new MembershipEventGrant(UUID.randomUUID(), member, tenant, event));
        return subject;
    }

    private static String importBody(String performanceId, String sourceVersion, boolean complete, String... seatCells) {
        StringBuilder rows = new StringBuilder();
        for (int index = 0; index < seatCells.length; index += 3) {
            if (!rows.isEmpty()) rows.append(',');
            rows.append("{\"reservationReference\":\"synthetic-reservation-").append(index).append("\",")
                    .append("\"zone\":\"").append(seatCells[index]).append("\",")
                    .append("\"row\":\"").append(seatCells[index + 1]).append("\",")
                    .append("\"seat\":\"").append(seatCells[index + 2]).append("\"}");
        }
        return "{\"performanceId\":\"" + performanceId + "\",\"sourceVersion\":\"" + sourceVersion
                + "\",\"completeSnapshot\":" + complete + ",\"rows\":[" + rows + "]}";
    }

    private static String bearer(String subject) {
        return "Bearer " + subject;
    }

    private static String id(String body) {
        Matcher matcher = ID.matcher(body);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private record Fixture(UUID tenantId, UUID eventId, UUID reportId, UUID evidenceId, UUID analysisId,
                           String subject, String sha256) { }
}
