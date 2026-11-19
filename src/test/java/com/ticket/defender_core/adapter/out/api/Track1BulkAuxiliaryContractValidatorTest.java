package com.ticket.defender_core.adapter.out.api;

import com.ticket.defender_core.adapter.out.api.dto.Track1BulkJobDto;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class Track1BulkAuxiliaryContractValidatorTest {
    private final UUID job=UUID.randomUUID();
    @Test void rejectsWrongJobAndDuplicateFindingTokens() {
        var wrongJob=new Track1BulkJobDto.PurchaseLimitEvaluationPage(UUID.randomUUID(),"COMPLETED",null,"COMPLETED",List.of());
        assertThatThrownBy(()->Track1BulkAuxiliaryContractValidator.evaluations(job,wrongJob)).isInstanceOf(FastApiResponseContractException.class);
        var finding=new Track1BulkJobDto.AccountLimitFinding("a".repeat(64),3,2,"p1",List.of(new Track1BulkJobDto.ReservationQuantity("b".repeat(64),3)));
        var duplicate=new Track1BulkJobDto.PurchaseLimitFindingPage(job,UUID.randomUUID(),"COMPLETED",null,"EVALUATED","p1",List.of(finding,finding));
        assertThatThrownBy(()->Track1BulkAuxiliaryContractValidator.findings(job,duplicate.performanceId(),duplicate)).isInstanceOf(FastApiResponseContractException.class);
    }
    @Test void rejectsUnverifiedQuantityThatClaimsATotal() {
        var observation=new Track1BulkJobDto.SharedSignalObservation("obs","PERFORMANCE","p","IP",2,2,1,2L,"UNAVAILABLE",new Track1BulkJobDto.SharedSignalCorroboration(0,0),"UNKNOWN",null,false);
        var page=new Track1BulkJobDto.SharedSignalObservationPage(job,"COMPLETED",null,"COMPLETED",List.of(observation));
        assertThatThrownBy(()->Track1BulkAuxiliaryContractValidator.shared(job,page)).isInstanceOf(FastApiResponseContractException.class);
    }
    @Test void acceptsTheActualDataPolicyVersionRulesForEveryEvaluationStatus() {
        UUID event=UUID.randomUUID(), performance=UUID.randomUUID();
        for (var evaluation : List.of(
                new Track1BulkJobDto.PurchaseLimitEvaluation(event,"event",performance,"EVALUATED","v1",0),
                new Track1BulkJobDto.PurchaseLimitEvaluation(event,"event",performance,"POLICY_NOT_CONFIRMED",null,0),
                new Track1BulkJobDto.PurchaseLimitEvaluation(event,"event",performance,"QUANTITY_DATA_INCOMPLETE","v1",0),
                new Track1BulkJobDto.PurchaseLimitEvaluation(event,"event",performance,"POLICY_TIME_NOT_APPLICABLE","v1",0))) {
            var page=new Track1BulkJobDto.PurchaseLimitEvaluationPage(job,"COMPLETED",null,"COMPLETED",List.of(evaluation));
            assertThatCode(()->Track1BulkAuxiliaryContractValidator.evaluations(job,page)).doesNotThrowAnyException();
        }
        var invalid=new Track1BulkJobDto.PurchaseLimitEvaluation(event,"event",performance,"POLICY_NOT_CONFIRMED","v1",0);
        assertThatThrownBy(()->Track1BulkAuxiliaryContractValidator.evaluations(job,new Track1BulkJobDto.PurchaseLimitEvaluationPage(job,"COMPLETED",null,"COMPLETED",List.of(invalid)))).isInstanceOf(FastApiResponseContractException.class);
    }
}
