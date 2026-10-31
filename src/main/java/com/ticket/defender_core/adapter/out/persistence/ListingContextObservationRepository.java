package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.ListingContextObservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ListingContextObservationRepository extends JpaRepository<ListingContextObservation, UUID> {
    List<ListingContextObservation> findByAnalysisIdIn(Collection<UUID> analysisIds);
}
