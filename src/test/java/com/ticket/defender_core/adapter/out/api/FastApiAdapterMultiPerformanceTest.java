package com.ticket.defender_core.adapter.out.api;

import com.ticket.defender_core.adapter.out.api.dto.MultiEventAnalysisDto;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 같은 공연 다회차 후보와 같은 계정 반복 동시 군집 관계의 JSON 경로 재검증. */
class FastApiAdapterMultiPerformanceTest {
    /** 실행마다 새로 만드는 테스트 전용 값입니다. 고정 자격 증명 문자열을 저장소에 두지 않습니다. */
    private static final String SECRET = "test-only-" + UUID.randomUUID() + UUID.randomUUID();
    private static final UUID PERFORMANCE_ONE = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID PERFORMANCE_TWO = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID VERSION_ONE = UUID.fromString("00000000-0000-4000-8000-000000000011");
    private static final UUID VERSION_TWO = UUID.fromString("00000000-0000-4000-8000-000000000012");
    private static final String SCOPES = """
            [{"eventId":"event-a","performanceId":"00000000-0000-4000-8000-000000000001","ledgerVersionId":"00000000-0000-4000-8000-000000000011","sourceBatchId":"batch-a"},
             {"eventId":"event-a","performanceId":"00000000-0000-4000-8000-000000000002","ledgerVersionId":"00000000-0000-4000-8000-000000000012","sourceBatchId":"batch-b"}]""";

    @Test
    void oneEventTwoPerformancesWithCoclusterAndPaymentAndIpWindowScoreSignalsIsAccepted() {
        var result = call(response("[\"ip_window:CORPORATE_EDU\",\"payment_hash\",\"refund_account_hash\"]",
                relation("stable_account_cocluster") + "," + relation("refund_account_hash"), 4, 0.3333333333333333), request(true));
        assertThat(result.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.distinctEventCount()).isEqualTo(1);
            assertThat(candidate.relationships()).extracting(MultiEventAnalysisDto.Relationship::signalType)
                    .containsExactly("stable_account_cocluster", "refund_account_hash");
        });
    }

    @Test
    void recurrenceFromTheEventCountIsRejected() {
        assertThatThrownBy(() -> call(response("[\"refund_account_hash\"]", relation("refund_account_hash"), 2, 0.0), request(true)))
                .isInstanceOf(FastApiResponseContractException.class);
    }

    @Test
    void coclusterMembersMustExistInEverySourceScope() {
        assertThatThrownBy(() -> call(response("[\"refund_account_hash\"]", relation("stable_account_cocluster"), 2, 0.3333333333333333), request(false)))
                .isInstanceOf(FastApiResponseContractException.class);
    }

    @Test
    void unknownRelationshipTypesAndStrongRelationsOutsideScoreSignalsAreRejected() {
        assertThatThrownBy(() -> call(response("[\"payment_hash\"]", relation("payment_hash"), 2, 0.3333333333333333), request(true)))
                .isInstanceOf(FastApiResponseContractException.class);
        assertThatThrownBy(() -> call(response("[\"payment_hash\"]", relation("refund_account_hash"), 2, 0.3333333333333333), request(true)))
                .isInstanceOf(FastApiResponseContractException.class);
        assertThatThrownBy(() -> call(response("[\"raw_ip\"]", relation("stable_account_cocluster"), 2, 0.3333333333333333), request(true)))
                .isInstanceOf(FastApiResponseContractException.class);
    }

    @Test
    void manifestRequiresTwoDistinctPerformancesAndTheNewScoreVersion() {
        FastApiAdapter adapter = adapter(RestClient.builder()).adapter();
        OffsetDateTime asOf = OffsetDateTime.parse("2026-11-25T00:00:00Z");
        UUID sourceEvent = UUID.fromString("00000000-0000-4000-8000-000000000031");
        var samePerformance = List.of(
                new MultiEventAnalysisDto.ArtifactLedgerScope("event-a", sourceEvent, PERFORMANCE_ONE, VERSION_ONE, "batch-a", null),
                new MultiEventAnalysisDto.ArtifactLedgerScope("event-a", sourceEvent, PERFORMANCE_ONE, VERSION_TWO, "batch-b", null));
        assertThatThrownBy(() -> adapter.createMultiEventJob(new MultiEventAnalysisDto.ArtifactCreate(manifest(asOf, samePerformance,
                FastApiAdapter.MULTI_PERFORMANCE_SCORE_VERSION)), "mei-test")).isInstanceOf(FastApiResponseContractException.class);
        var twoPerformances = List.of(
                new MultiEventAnalysisDto.ArtifactLedgerScope("event-a", sourceEvent, PERFORMANCE_ONE, VERSION_ONE, "batch-a", null),
                new MultiEventAnalysisDto.ArtifactLedgerScope("event-a", sourceEvent, PERFORMANCE_TWO, VERSION_TWO, "batch-b", null));
        assertThatThrownBy(() -> adapter.createMultiEventJob(new MultiEventAnalysisDto.ArtifactCreate(manifest(asOf, twoPerformances,
                "score-v2-exp2-multi-event-recurrence")), "mei-test")).isInstanceOf(FastApiResponseContractException.class);
    }

    private static MultiEventAnalysisDto.ArtifactManifest manifest(OffsetDateTime asOf, List<MultiEventAnalysisDto.ArtifactLedgerScope> scopes, String scoreVersion) {
        return new MultiEventAnalysisDto.ArtifactManifest("mei-test-artifact", "mei-test-artifact", "s3-version-1", "a".repeat(64), 10,
                "multi-event-exp2-ndjson-v1", UUID.randomUUID(), UUID.randomUUID(), "supplier-a", "TRACK1_CONNECTION", "key-v1", "d".repeat(64),
                asOf, asOf.minusDays(30), asOf.minusDays(1), scoreVersion, "projection-v2.2-exp2-ipwindow-softhub", scopes);
    }

    private MultiEventAnalysisDto.Response call(String responseBody, MultiEventAnalysisDto.Request request) {
        var built = adapter(RestClient.builder());
        built.server().expect(requestTo("https://fastapi.test/api/v2/multi-event/analyze"))
                .andRespond(withSuccess(responseBody.formatted(request.analysisId(), request.ledgerVersionSetDigest()), MediaType.APPLICATION_JSON));
        return built.adapter().analyzeMultiEvent(request);
    }

    private record Built(FastApiAdapter adapter, MockRestServiceServer server) { }

    private static Built adapter(RestClient.Builder builder) {
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FastApiAdapter adapter = new FastApiAdapter(builder.build());
        ReflectionTestUtils.setField(adapter, "fastApiUrl", "https://fastapi.test");
        ReflectionTestUtils.setField(adapter, "track1InternalSharedSecret", SECRET);
        return new Built(adapter, server);
    }

    private static String relation(String type) {
        return "{\"signalType\":\"" + type + "\",\"accountIds\":[\"" + "a".repeat(64) + "\",\"" + "b".repeat(64) + "\"],\"sourceScopes\":" + SCOPES + "}";
    }

    private static String response(String available, String relationships, int verified, double recurrence) {
        double behavior = 0.5 * 0.5 + 0.5 * recurrence;
        double score = Math.round(0.8 * 0.68 * behavior * 10_000.0) / 10_000.0;
        String band = score >= .40 ? "HIGH" : score >= .20 ? "MEDIUM" : "LOW";
        return """
                {"analysisId":"%s","ledgerVersionSetDigest":"%s",
                 "scoreVersion":"score-v2-exp2-multi-performance-recurrence","projectionScoreVersion":"score-v2-exp2",
                 "projectionVersion":"projection-v2.2-exp2-ipwindow-softhub","algorithm":"GDS_LOUVAIN","algorithmVersion":"gds-2.6.6",
                 "candidates":[{"candidateId":"multi-""" + "1".repeat(64) + """
                ","priorityBand":"BAND",
                   "scoreVersion":"score-v2-exp2-multi-performance-recurrence","projectionVersion":"projection-v2.2-exp2-ipwindow-softhub",
                   "distinctAccountCount":2,"distinctEventCount":1,"distinctPerformanceCount":2,"verifiedRelationshipCount":VERIFIED,
                   "sourceScopes":SCOPES,"availableSignals":AVAILABLE,"excludedSignals":[],"unavailableSignals":["ip_window"],
                   "normalSharingPossibilities":["NORMAL_SHARED_POSSIBLE"],
                   "scoreBreakdown":{"L":0.8,"G":0.68,"B":BEHAVIOR,"Q":0.5,"R":RECURRENCE,"score":SCORE,"quantityStatus":"NEUTRAL_NO_POLICY","quantityReason":"UNKNOWN"},
                   "familyId":"FAMILY_ID","parentCandidateId":null,"childCount":0,"familyScore":SCORE,"reasons":[],
                   "memberReasons":[{"accountReferenceHmac":"MEMBER_A","reasons":[]},{"accountReferenceHmac":"MEMBER_B","reasons":[]}],
                   "memberAccountIds":["MEMBER_A","MEMBER_B"],"relationships":[RELATIONSHIPS]}]}
                """.replace("BAND", band).replace("VERIFIED", Integer.toString(verified)).replace("SCOPES", SCOPES)
                .replace("AVAILABLE", available).replace("BEHAVIOR", Double.toString(behavior)).replace("RECURRENCE", Double.toString(recurrence))
                .replace("SCORE", Double.toString(score)).replace("MEMBER_A", "a".repeat(64)).replace("MEMBER_B", "b".repeat(64))
                .replace("FAMILY_ID", "multi-"+"1".repeat(64)).replace("RELATIONSHIPS", relationships);
    }

    /** bothScopes=false이면 계정 b는 두 번째 회차에만 있습니다. */
    private static MultiEventAnalysisDto.Request request(boolean bothScopes) {
        OffsetDateTime asOf = OffsetDateTime.parse("2026-11-25T00:00:00Z");
        var scopes = List.of(new MultiEventAnalysisDto.LedgerScope("event-a", PERFORMANCE_ONE, VERSION_ONE, "batch-a"),
                new MultiEventAnalysisDto.LedgerScope("event-a", PERFORMANCE_TWO, VERSION_TWO, "batch-b"));
        var tickets = new java.util.ArrayList<MultiEventAnalysisDto.Ticket>();
        tickets.add(ticket("a", "1", PERFORMANCE_ONE, VERSION_ONE, 1L, asOf));
        tickets.add(ticket(bothScopes ? "b" : "c", "2", PERFORMANCE_ONE, VERSION_ONE, 2L, asOf));
        tickets.add(ticket("a", "3", PERFORMANCE_TWO, VERSION_TWO, 1L, asOf));
        tickets.add(ticket("b", "4", PERFORMANCE_TWO, VERSION_TWO, 2L, asOf));
        return new MultiEventAnalysisDto.Request(UUID.fromString("2136558c-2326-4430-a46d-0793494e9b49"),
                UUID.fromString("2136558c-2326-4430-a46d-0793494e9b50"), "d".repeat(64), asOf, asOf.minusDays(30), asOf.minusDays(1),
                FastApiAdapter.MULTI_PERFORMANCE_SCORE_VERSION, "projection-v2.2-exp2-ipwindow-softhub", scopes, tickets);
    }

    private static MultiEventAnalysisDto.Ticket ticket(String account, String reservation, UUID performance, UUID version, long ordinal, OffsetDateTime asOf) {
        return new MultiEventAnalysisDto.Ticket(account.repeat(64), reservation.repeat(64), "event-a", performance, version, ordinal, "supplier-a",
                "TRACK1_CONNECTION", "key-v1", asOf.minusDays(1), 1, null, null, null, null, "8".repeat(64), null, null, "7".repeat(64),
                "e".repeat(64), null, "CORPORATE_EDU", "UNKNOWN", "QUANTITY_NOT_CONFIRMED", "source-v1");
    }
}
