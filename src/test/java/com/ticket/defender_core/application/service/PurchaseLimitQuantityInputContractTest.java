package com.ticket.defender_core.application.service;

import com.ticket.defender_core.domain.PurchaseLimitPolicy;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class PurchaseLimitQuantityInputContractTest {
    @Test void missingActualPurchaseTimeIsQuantityDataIncomplete() {
        var assessment=PurchaseLimitQuantityInputContract.assess(confirmedPolicy(),new PurchaseLimitQuantityInputContract.IndividualTicket("account","reservation","ticket",null,2,PurchaseLimitQuantityInputContract.TicketStatus.ACTIVE,"provider-v1",Instant.parse("2026-11-09T00:00:00Z")));
        assertThat(assessment.status()).isEqualTo(PurchaseLimitQuantityInputContract.AssessmentStatus.QUANTITY_DATA_INCOMPLETE);
    }
    @Test void aggregateSnapshotIsKeptDistinctAndNotSentToCurrentDataV2() {
        var assessment=PurchaseLimitQuantityInputContract.assess(confirmedPolicy(),new PurchaseLimitQuantityInputContract.AccountLimitSnapshot("account","performance",3,2,"provider-basis-v1",Instant.parse("2026-11-09T00:00:00Z"),"provider-v1"));
        assertThat(assessment.status()).isEqualTo(PurchaseLimitQuantityInputContract.AssessmentStatus.ACCOUNT_SNAPSHOT_NOT_SUPPORTED_BY_DATA_V2);
    }
    @Test void policyWithoutSemanticsRemainsActiveButIsNotEvaluated() {
        var legacy=new PurchaseLimitPolicy(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),2,"v1",LocalDateTime.of(2026,1,1,0,0),null,"customer","basis",UUID.randomUUID(),LocalDateTime.now());
        var assessment=PurchaseLimitQuantityInputContract.assess(legacy,new PurchaseLimitQuantityInputContract.IndividualTicket("account","reservation","ticket",Instant.parse("2026-11-08T00:00:00Z"),2,PurchaseLimitQuantityInputContract.TicketStatus.ACTIVE,"provider-v1",Instant.parse("2026-11-09T00:00:00Z")));
        assertThat(assessment.status()).isEqualTo(PurchaseLimitQuantityInputContract.AssessmentStatus.POLICY_NOT_CONFIRMED);
    }
    private static PurchaseLimitPolicy confirmedPolicy() { return new PurchaseLimitPolicy(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),2,"v2",LocalDateTime.of(2026,1,1,0,0),null,"customer","basis",PurchaseLimitPolicy.LimitSubject.ACCOUNT,PurchaseLimitPolicy.ApplicationScope.PERFORMANCE,null,PurchaseLimitPolicy.CancellationRefundRule.NETTED,true,UUID.randomUUID(),LocalDateTime.now()); }
}
