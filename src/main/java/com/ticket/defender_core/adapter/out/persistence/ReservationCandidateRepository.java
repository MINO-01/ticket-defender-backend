package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.ReservationCandidate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReservationCandidateRepository extends JpaRepository<ReservationCandidate, UUID> {
    List<ReservationCandidate> findTop2ByBatchIdAndZoneAndRowNumAndSeatNum(UUID batchId, String zone,
            String rowNum, String seatNum);
}
