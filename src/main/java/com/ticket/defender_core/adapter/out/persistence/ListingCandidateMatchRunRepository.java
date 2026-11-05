package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.ListingCandidateMatchRun;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ListingCandidateMatchRunRepository extends JpaRepository<ListingCandidateMatchRun, UUID> {
    Optional<ListingCandidateMatchRun> findByCreatedByMembershipIdAndAnalysisIdAndIdempotencyKeyDigest(
            UUID membershipId, UUID analysisId, String keyDigest);

    Optional<ListingCandidateMatchRun> findByActiveAnalysisId(UUID analysisId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select run from ListingCandidateMatchRun run where run.activeAnalysisId = :analysisId")
    Optional<ListingCandidateMatchRun> findByActiveAnalysisIdForUpdate(@Param("analysisId") UUID analysisId);

    Page<ListingCandidateMatchRun> findByAnalysisId(UUID analysisId, Pageable pageable);
}
