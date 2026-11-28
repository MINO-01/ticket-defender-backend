package com.ticket.defender_core.adapter.out.api;

import com.ticket.defender_core.adapter.out.api.dto.MultiEventAnalysisDto;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

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
    void defaultRequestOmitsCancellationContractAndEnabledContractSerializesSeparateRows() throws Exception {
        var mapper = JsonMapper.builder().build();
        var existing = request(true);
        String oldBody = mapper.writeValueAsString(existing);
        assertThat(oldBody).doesNotContain("cancelledTickets", "seatZone", "seatRow", "seatNumber", "reservedAt", "paidAt", "accountCreatedAt");

        var first = existing.ledgerScopes().getFirst();
        var cancelled = new MultiEventAnalysisDto.CancelledTicket("c".repeat(64), "d".repeat(64),
                first.eventId(), first.performanceId(), first.ledgerVersionId(), "supplier-a", "TRACK1_CONNECTION",
                "key-v1", "source-v1", 3, "BALCONY", "R1", "12", OffsetDateTime.parse("2026-11-20T10:00:00Z"));
        var enabled = new MultiEventAnalysisDto.Request(existing.analysisId(), existing.tenantId(),
                existing.ledgerVersionSetDigest(), existing.analysisAsOf(), existing.analysisStartAt(),
                existing.analysisEndAt(), existing.scoreVersion(), existing.projectionVersion(),
                existing.ledgerScopes(), existing.tickets(), List.of(cancelled));
        String enabledBody = mapper.writeValueAsString(enabled);
        assertThat(enabledBody).contains("cancelledTickets", "accountHmac", "ticketHmac", "cancelledAt")
                .doesNotContain("rawTicketNumber");
    }

    @Test
    void activeTicketV3SeatAndTimeFieldsUseTheOptionalCamelCaseContract() throws Exception {
        var mapper = JsonMapper.builder().build();
        var base = ticket("a", "1", PERFORMANCE_ONE, VERSION_ONE, 1L,
                OffsetDateTime.parse("2026-11-25T00:00:00Z"));
        var enriched = new MultiEventAnalysisDto.Ticket(base.accountId(), base.reservationNo(), base.eventId(),
                base.performanceId(), base.ledgerVersionId(), base.sourceRowOrdinal(), base.supplierNamespace(),
                base.hmacPurpose(), base.hmacKeyVersion(), base.purchasedAt(), base.effectiveTicketQuantity(),
                base.confirmedPurchaseLimit(), base.quantityPolicySnapshotId(), base.quantityPolicyVersion(),
                base.quantitySourceVersion(), base.paymentHash(), base.addressHash(), base.deviceIdHash(),
                base.ipHash(), base.refundAccountHash(), base.phoneHash(), base.ipNetworkType(),
                base.quantityStatus(), base.quantityReason(), base.sourceVersion(), "BALCONY", "R1", "12",
                OffsetDateTime.parse("2026-11-20T09:00:00Z"), OffsetDateTime.parse("2026-11-20T09:05:00Z"),
                OffsetDateTime.parse("2024-01-03T00:00:00Z"));

        String body = mapper.writeValueAsString(enriched);

        assertThat(body).contains("\"seatZone\":\"BALCONY\"", "\"seatRow\":\"R1\"", "\"seatNumber\":\"12\"")
                .contains("\"reservedAt\"", "\"paidAt\"", "\"accountCreatedAt\"");
    }

    @Test
    void exp3QuantityEvidenceUsesSeparateVersionedPseudonymousRecords() throws Exception {
        var mapper = JsonMapper.builder().build();
        var asOf = OffsetDateTime.parse("2026-11-28T00:00:00Z");
        var start = OffsetDateTime.parse("2026-11-01T00:00:00Z");
        var analysis = UUID.randomUUID();
        var tenant = UUID.randomUUID();
        var performance = UUID.randomUUID();
        var ledgerVersion = UUID.randomUUID();
        var manifest = new MultiEventAnalysisDto.Exp3QuantityScopeManifestRecord(
                "EXP3_QUANTITY_SCOPE_MANIFEST", "exp3-quantity-evidence-v1", analysis, tenant,
                "supplier-a", "event-a", performance, ledgerVersion, "TRACK1_CONNECTION", "key-v1",
                "source-v1", asOf, start, asOf, true, "COMPLETE", "NETTED", "DEDUPLICATED",
                "LATEST_REVISION_PER_TICKET_REFERENCE", true, "CONFIRMED", UUID.randomUUID(),
                "policy-v1", "source-v1", 4, 1, 2, 1, 1, 3, 4);
        var evidence = new MultiEventAnalysisDto.Exp3AccountQuantityEvidenceRecord(
                "EXP3_ACCOUNT_QUANTITY_EVIDENCE", "exp3-quantity-evidence-v1", analysis, tenant,
                "supplier-a", "event-a", performance, ledgerVersion, "TRACK1_CONNECTION", "key-v1",
                "a".repeat(64), "source-v1", asOf, start, asOf, 2, 1, 1, 3, 4);

        String manifestJson = mapper.writeValueAsString(manifest);
        String evidenceJson = mapper.writeValueAsString(evidence);

        assertThat(manifestJson).contains("EXP3_QUANTITY_SCOPE_MANIFEST", "exp3-quantity-evidence-v1",
                "expectedTicketUnitRowCount", "expectedOrderLineTicketQuantity", "confirmedPurchaseLimit");
        assertThat(evidenceJson).contains("EXP3_ACCOUNT_QUANTITY_EVIDENCE", "accountHmac",
                "ticketUnitRowCount", "orderLineTicketQuantity", "actualTicketQuantity")
                .doesNotContain("accountId", "reservationNo", "ticketReference");
    }

    @Test
    void multiEventAdapterAcceptsSeparatePseudonymousCancellationEvidence() {
        var existing = request(true);
        var scope = existing.ledgerScopes().getFirst();
        var cancelled = new MultiEventAnalysisDto.CancelledTicket("e".repeat(64), "f".repeat(64),
                scope.eventId(), scope.performanceId(), scope.ledgerVersionId(), "supplier-a", "TRACK1_CONNECTION",
                "key-v1", "source-v1", 3, "BALCONY", "R1", "12",
                OffsetDateTime.parse("2026-11-20T10:00:00Z"));
        var withCancellation = new MultiEventAnalysisDto.Request(existing.analysisId(), existing.tenantId(),
                existing.ledgerVersionSetDigest(), existing.analysisAsOf(), existing.analysisStartAt(),
                existing.analysisEndAt(), existing.scoreVersion(), existing.projectionVersion(),
                existing.ledgerScopes(), existing.tickets(), List.of(cancelled));

        var response = call(response("[\"refund_account_hash\"]", relation("refund_account_hash"),
                2, 0.3333333333333333), withCancellation);

        assertThat(response.candidates()).hasSize(1);
    }

    @Test
    void zeroCountArtifactManifestPreservesV1SerializationAndV2CarriesCancellationCount() throws Exception {
        var mapper = JsonMapper.builder().build();
        var scopes = List.of(
                new MultiEventAnalysisDto.ArtifactLedgerScope("event-a", UUID.randomUUID(), PERFORMANCE_ONE, VERSION_ONE, "batch-a", null),
                new MultiEventAnalysisDto.ArtifactLedgerScope("event-a", UUID.randomUUID(), PERFORMANCE_TWO, VERSION_TWO, "batch-b", null));
        var v1 = manifest(OffsetDateTime.parse("2026-11-25T00:00:00Z"), scopes,
                "score-v2-exp2-multi-performance-recurrence");
        assertThat(mapper.writeValueAsString(v1)).doesNotContain("cancelledTicketRecordCount");
        var v2 = new MultiEventAnalysisDto.ArtifactManifest(v1.artifactId(), v1.objectKey(), v1.objectVersion(),
                v1.sha256(), v1.recordCount(), 1, "multi-event-exp2-ndjson-v2", v1.analysisId(), v1.tenantId(),
                v1.supplierNamespace(), v1.hmacPurpose(), v1.hmacKeyVersion(), v1.ledgerVersionSetDigest(),
                v1.analysisAsOf(), v1.analysisStartAt(), v1.analysisEndAt(), v1.scoreVersion(),
                v1.projectionVersion(), v1.ledgerScopes());
        assertThat(mapper.writeValueAsString(v2)).contains("\"cancelledTicketRecordCount\":1")
                .contains("multi-event-exp2-ndjson-v2");
    }

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
