package com.ticket.defender_core.application.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import com.ticket.defender_core.adapter.out.api.FastApiResponseContractException;
import com.ticket.defender_core.adapter.out.api.dto.Track1BulkJobDto;

import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BulkLedgerTrack1BulkWorkerTest {
    @Test
    void rejectsLedgerRowsInsteadOfSilentlyDroppingMissingSignals() {
        assertThat(BulkLedgerTrack1BulkWorker.inputFailure("ACTIVE", "key-v1", null, null,
                "source-v1", null, null, null, null, null)).isEqualTo("LEDGER_SIGNAL_MISSING");
    }

    @Test
    void rejectsMismatchedLedgerAndSignalHmacKeyVersions() {
        assertThat(BulkLedgerTrack1BulkWorker.inputFailure("ACTIVE", "key-v1", "key-v2", "TRACK1_CONNECTION",
                "source-v1", "source-v1", "a".repeat(64), null, null, null)).isEqualTo("LEDGER_HMAC_KEY_VERSION_MISMATCH");
    }

    @Test
    void rejectsUnsettledTicketStatesAndAcceptsOnlySourcedActiveRows() {
        assertThat(BulkLedgerTrack1BulkWorker.inputFailure("REFUNDED", "key-v1", "key-v1", "TRACK1_CONNECTION",
                "source-v1", "source-v1", "a".repeat(64), null, null, null)).isEqualTo("LEDGER_TICKET_STATUS_NOT_SUPPORTED");
        assertThat(BulkLedgerTrack1BulkWorker.inputFailure("ACTIVE", "key-v1", "key-v1", "TRACK1_CONNECTION",
                "source-v1", "source-v1", "a".repeat(64), null, null, null)).isNull();
    }

    @Test
    void onlyTheCurrentFenceMayRenewALongRunningLease() {
        assertThat(BulkLedgerTrack1BulkWorker.fenceUpdateAccepted(1)).isTrue();
        assertThat(BulkLedgerTrack1BulkWorker.fenceUpdateAccepted(0)).isFalse();
        assertThat(BulkLedgerTrack1BulkWorker.fenceUpdateAccepted(2)).isFalse();
    }

    @Test
    void refusesToRewriteAnS3VersionThatCannotSatisfyTheDataManifestContract() {
        assertThat(BulkLedgerTrack1BulkWorker.manifestObjectVersionCompatible("version-1._A")).isTrue();
        assertThat(BulkLedgerTrack1BulkWorker.manifestObjectVersionCompatible("version/1")).isFalse();
    }

    @Test
    void resumesBeforeAndAfterArtifactStagesWithoutCallingTheDataRetryEndpoint() {
        assertThat(BulkLedgerTrack1BulkWorker.recoveryStage("PREPARING", false)).isEqualTo("PREPARING");
        assertThat(BulkLedgerTrack1BulkWorker.recoveryStage("REGISTERING", false)).isEqualTo("REGISTERING");
        assertThat(BulkLedgerTrack1BulkWorker.recoveryStage("RETRYABLE", false)).isEqualTo("PREPARING");
        assertThat(BulkLedgerTrack1BulkWorker.recoveryStage("RUNNING", true)).isEqualTo("RUNNING");
    }

    @Test
    void onlyActualFailuresAdvanceTheRetryLimit() {
        assertThat(BulkLedgerTrack1BulkWorker.failureState(0, "PREPARING")).isEqualTo("PREPARING");
        assertThat(BulkLedgerTrack1BulkWorker.failureState(1, "RETRYABLE")).isEqualTo("RETRYABLE");
        assertThat(BulkLedgerTrack1BulkWorker.failureState(2, "RETRYABLE")).isEqualTo("FAILED");
    }

    @Test
    void configuresConnectorJForwardOnlyStreamingInsteadOfBufferingTheWholeLedger() throws Exception {
        AtomicInteger fetchSize=new AtomicInteger();
        PreparedStatement statement=(PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{PreparedStatement.class},
                (proxy, method, arguments) -> { if ("setFetchSize".equals(method.getName())) fetchSize.set((Integer) arguments[0]); return null; });
        BulkLedgerTrack1BulkWorker.configureMySqlStreaming(statement);
        assertThat(fetchSize.get()).isEqualTo(Integer.MIN_VALUE);
    }

    @Test
    void rejectsRepeatedOrNonNumericDataCursorsInsteadOfTreatingAPartialPageAsComplete() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> BulkLedgerTrack1BulkWorker.rejectCursor("10", "10"))
                .isInstanceOf(RuntimeException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> BulkLedgerTrack1BulkWorker.rejectCursor("10", "next"))
                .isInstanceOf(RuntimeException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> BulkLedgerTrack1BulkWorker.rejectCursor("10", "9"))
                .isInstanceOf(RuntimeException.class);
        BulkLedgerTrack1BulkWorker.rejectCursor("10", "11");
    }

    @Test
    void keepsGrossPolicyEvidenceOutOfTheDataScopeContract() {
        var scope=BulkLedgerTrack1BulkWorker.scopeForData(UUID.randomUUID(),"event",UUID.randomUUID(),"source",
                OffsetDateTime.now(ZoneOffset.UTC),"CONFIRMED",new com.ticket.defender_core.adapter.out.api.dto.Track1BulkJobDto.ConfirmedPolicy(2,"v1",OffsetDateTime.now(ZoneOffset.UTC),null),"INCOMPLETE","GROSS","GROSS_RULE_NOT_SUPPORTED");
        assertThat(scope.policyVerificationStatus()).isEqualTo("POLICY_NOT_CONFIRMED");
        assertThat(scope.policy()).isNull();
        assertThat(scope.quantityDataStatus()).isEqualTo("INCOMPLETE");
        assertThat(scope.cancellationHandling()).isEqualTo("NOT_CONFIRMED");
        assertThat(scope.dataCompletenessReason()).isEqualTo("GROSS_RULE_NOT_SUPPORTED");
    }

    @Test
    void requiresExactlyOneSavedEvaluationForTheJobPerformanceBeforeCompletion() {
        assertThat(BulkLedgerTrack1BulkWorker.exactlyOneRequiredEvaluation(1,1)).isTrue();
        assertThat(BulkLedgerTrack1BulkWorker.exactlyOneRequiredEvaluation(0,0)).isFalse();
        assertThat(BulkLedgerTrack1BulkWorker.exactlyOneRequiredEvaluation(1,2)).isFalse();
        assertThat(BulkLedgerTrack1BulkWorker.sameEvaluationCursor("10","10")).isTrue();
        assertThat(BulkLedgerTrack1BulkWorker.sameEvaluationCursor("10","11")).isFalse();
    }

    @Test
    void blocksDataResponseContractViolationsButRetriesDependencyFailures() {
        assertThat(BulkLedgerTrack1BulkWorker.dataContractFailureCode(new FastApiResponseContractException("invalid")))
                .isEqualTo("DATA_RESPONSE_CONTRACT_INVALID");
        assertThat(BulkLedgerTrack1BulkWorker.dataContractFailureCode(new ResourceAccessException("offline"))).isNull();
        assertThat(BulkLedgerTrack1BulkWorker.dataContractFailureCode(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE))).isNull();
    }

    @Test
    void distinguishesSharedObservationReplaysFromCrossCursorDuplicates() {
        assertThat(BulkLedgerTrack1BulkWorker.sameSharedCursor("10", "10")).isTrue();
        assertThat(BulkLedgerTrack1BulkWorker.sameSharedCursor(null, null)).isTrue();
        assertThat(BulkLedgerTrack1BulkWorker.sameSharedCursor("10", "11")).isFalse();
    }

    @Test
    void acceptsOnlyMemberPagesForTheClaimedJobAndCluster() {
        UUID jobId = UUID.randomUUID();
        String clusterId = "cluster-1";
        var matching = new Track1BulkJobDto.Members(jobId, clusterId, "500", java.util.List.of(
                new Track1BulkJobDto.Member("a".repeat(64), "b".repeat(64), "event"),
                new Track1BulkJobDto.Member("a".repeat(64), "c".repeat(64), "event")
        ));

        assertThat(BulkLedgerTrack1BulkWorker.memberPageMatches(jobId, clusterId, matching)).isTrue();
        assertThat(BulkLedgerTrack1BulkWorker.memberPageMatches(UUID.randomUUID(), clusterId, matching)).isFalse();
        assertThat(BulkLedgerTrack1BulkWorker.memberPageMatches(jobId, "cluster-2", matching)).isFalse();
    }

    @Test
    void memberPageCursorMustMakeForwardNumericProgress() {
        BulkLedgerTrack1BulkWorker.rejectCursor(null, "500");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> BulkLedgerTrack1BulkWorker.rejectCursor("500", "500"))
                .isInstanceOf(RuntimeException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> BulkLedgerTrack1BulkWorker.rejectCursor("500", "499"))
                .isInstanceOf(RuntimeException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> BulkLedgerTrack1BulkWorker.rejectCursor("500", "cursor"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void completesMemberCollectionOnlyWhenStoredDistinctAccountsMatchTheClusterDeclaration() {
        // Two reservation rows for one account are valid and must remain one distinct cluster member.
        assertThat(BulkLedgerTrack1BulkWorker.memberAccountCountMatches(1, 1)).isTrue();
        // A missing account or a duplicate page that displaces an account must not complete the cluster.
        assertThat(BulkLedgerTrack1BulkWorker.memberAccountCountMatches(1, 2)).isFalse();
        assertThat(BulkLedgerTrack1BulkWorker.memberAccountCountMatches(2, 1)).isFalse();
    }
}
