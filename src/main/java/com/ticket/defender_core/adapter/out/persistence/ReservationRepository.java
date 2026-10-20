package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    /** 고객사·공연·회차가 모두 일치하는 후보를 최대 두 건 조회해 중복 좌석을 판별합니다. */
    List<Reservation> findTop2ByTenantIdAndEventIdAndPerformanceRefAndZoneAndRowNumAndSeatNum(
            UUID tenantId, UUID eventId, String performanceRef, String zone, String rowNum, String seatNum);
}
