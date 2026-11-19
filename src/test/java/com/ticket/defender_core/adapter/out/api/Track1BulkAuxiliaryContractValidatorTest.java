package com.ticket.defender_core.adapter.out.api;

import com.ticket.defender_core.adapter.out.api.dto.Track1BulkJobDto;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}
