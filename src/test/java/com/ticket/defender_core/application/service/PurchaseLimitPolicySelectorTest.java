package com.ticket.defender_core.application.service;

import com.ticket.defender_core.domain.PurchaseLimitPolicy;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class PurchaseLimitPolicySelectorTest {
    private static final UUID PERFORMANCE=UUID.randomUUID();
    @Test void salesPhaseOverridesPerformanceAndEventPolicy() {
        var result=PurchaseLimitPolicySelector.select(List.of(policy("event",PurchaseLimitPolicy.ApplicationScope.EVENT,null,2,PurchaseLimitPolicy.LimitSubject.ACCOUNT),policy("performance",PurchaseLimitPolicy.ApplicationScope.PERFORMANCE,null,3,PurchaseLimitPolicy.LimitSubject.ACCOUNT),policy("presale",PurchaseLimitPolicy.ApplicationScope.SALES_PHASE,"presale",1,PurchaseLimitPolicy.LimitSubject.ACCOUNT)),context(PurchaseLimitPolicy.LimitSubject.ACCOUNT,true,"presale"));
        assertThat(result.status()).isEqualTo(PurchaseLimitPolicySelector.Status.SELECTED); assertThat(result.policy().getPolicyVersion()).isEqualTo("presale");
    }
    @Test void personPolicyWithoutAuthorizedIdentityBasisIsNotEvaluated() {
        var result=PurchaseLimitPolicySelector.select(List.of(policy("person",PurchaseLimitPolicy.ApplicationScope.PERFORMANCE,null,2,PurchaseLimitPolicy.LimitSubject.PERSON)),context(PurchaseLimitPolicy.LimitSubject.PERSON,false,null));
        assertThat(result.status()).isEqualTo(PurchaseLimitPolicySelector.Status.POLICY_NOT_CONFIRMED);
    }
    private static PurchaseLimitPolicySelector.Context context(PurchaseLimitPolicy.LimitSubject subject,boolean authorized,String phase) { return new PurchaseLimitPolicySelector.Context(PERFORMANCE,phase,subject,authorized,LocalDateTime.of(2026,11,9,0,0)); }
    private static PurchaseLimitPolicy policy(String version,PurchaseLimitPolicy.ApplicationScope scope,String phase,int maximum,PurchaseLimitPolicy.LimitSubject subject) { return new PurchaseLimitPolicy(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),PERFORMANCE,maximum,version,LocalDateTime.of(2026,1,1,0,0),null,"customer","basis",subject,scope,phase,PurchaseLimitPolicy.CancellationRefundRule.NETTED,true,UUID.randomUUID(),LocalDateTime.now()); }
}
