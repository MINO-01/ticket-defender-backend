package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.Reservation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:reservation-scope;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"
})
class ReservationRepositoryTest {
    private static final UUID TENANT_A = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID EVENT_A = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID EVENT_B = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID EVENT_C = UUID.fromString("55555555-5555-4555-8555-555555555555");

    @Autowired private ReservationRepository reservations;

    @Test
    void sameSeatInOtherTenantEventOrPerformanceIsExcluded() {
        reservations.saveAllAndFlush(List.of(
                reservation(TENANT_A, EVENT_A, "round-1", "res-a"),
                reservation(TENANT_A, EVENT_B, "round-1", "res-b"),
                reservation(TENANT_A, EVENT_A, "round-2", "res-c"),
                reservation(TENANT_B, EVENT_C, "round-1", "res-d"),
                new Reservation("res-legacy", "account-legacy", "VIP", "10", "5", 100_000L)
        ));

        assertThat(find(TENANT_A, EVENT_A, "round-1"))
                .extracting(Reservation::getReservationNo).containsExactly("res-a");
        assertThat(find(TENANT_A, EVENT_B, "round-1"))
                .extracting(Reservation::getReservationNo).containsExactly("res-b");
        assertThat(find(TENANT_A, EVENT_A, "round-2"))
                .extracting(Reservation::getReservationNo).containsExactly("res-c");
        assertThat(find(TENANT_B, EVENT_C, "round-1"))
                .extracting(Reservation::getReservationNo).containsExactly("res-d");
        assertThat(find(TENANT_A, EVENT_A, "unknown-round")).isEmpty();
    }

    @Test
    void duplicateSeatInSameScopeReturnsTwoCandidatesForAmbiguityGuard() {
        reservations.saveAllAndFlush(List.of(
                reservation(TENANT_A, EVENT_A, "round-1", "res-1"),
                reservation(TENANT_A, EVENT_A, "round-1", "res-2"),
                reservation(TENANT_A, EVENT_A, "round-1", "res-3")
        ));

        assertThat(find(TENANT_A, EVENT_A, "round-1")).hasSize(2);
    }

    private List<Reservation> find(UUID tenantId, UUID eventId, String performanceRef) {
        return reservations.findTop2ByTenantIdAndEventIdAndPerformanceRefAndZoneAndRowNumAndSeatNum(
                tenantId, eventId, performanceRef, "VIP", "10", "5");
    }

    private Reservation reservation(UUID tenantId, UUID eventId, String performanceRef, String reservationNo) {
        return new Reservation(tenantId, eventId, performanceRef, reservationNo,
                "account-1", "VIP", "10", "5", 100_000L);
    }
}
