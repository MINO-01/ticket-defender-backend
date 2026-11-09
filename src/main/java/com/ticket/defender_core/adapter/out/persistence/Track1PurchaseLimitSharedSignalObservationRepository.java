package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.Track1PurchaseLimitSharedSignalObservation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface Track1PurchaseLimitSharedSignalObservationRepository extends JpaRepository<Track1PurchaseLimitSharedSignalObservation, UUID> {
    List<Track1PurchaseLimitSharedSignalObservation> findByRunIdIn(Collection<UUID> runIds);
}
