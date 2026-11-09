package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.Track1PurchaseLimitFinding;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface Track1PurchaseLimitFindingRepository extends JpaRepository<Track1PurchaseLimitFinding, UUID> {
    List<Track1PurchaseLimitFinding> findByRunIdIn(Collection<UUID> runIds);
}
