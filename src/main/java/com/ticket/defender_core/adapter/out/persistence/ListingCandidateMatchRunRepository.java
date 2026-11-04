package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.ListingCandidateMatchRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ListingCandidateMatchRunRepository extends JpaRepository<ListingCandidateMatchRun, UUID> {
    Optional<ListingCandidateMatchRun> findByCreatedByMembershipIdAndAnalysisIdAndIdempotencyKeyDigest(
            UUID membershipId, UUID analysisId, String keyDigest);

    Optional<ListingCandidateMatchRun> findByActiveAnalysisId(UUID analysisId);

    Page<ListingCandidateMatchRun> findByAnalysisId(UUID analysisId, Pageable pageable);
}
