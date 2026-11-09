package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.Track1PurchaseLimitFindingReservation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface Track1PurchaseLimitFindingReservationRepository extends JpaRepository<Track1PurchaseLimitFindingReservation, UUID> {
    List<Track1PurchaseLimitFindingReservation> findByFindingIdIn(Collection<UUID> findingIds);
}
