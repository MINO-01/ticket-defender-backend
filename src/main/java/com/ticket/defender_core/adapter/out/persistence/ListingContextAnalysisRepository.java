package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.ListingContextAnalysis;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ListingContextAnalysisRepository extends JpaRepository<ListingContextAnalysis, UUID> {
    Optional<ListingContextAnalysis> findByCreatedByMembershipIdAndEvidenceIdAndIdempotencyKeyDigest(
            UUID membershipId, UUID evidenceId, String keyDigest);
    Optional<ListingContextAnalysis> findByActiveEvidenceId(UUID evidenceId);
    Page<ListingContextAnalysis> findByFanReportIdAndEvidenceId(UUID fanReportId, UUID evidenceId, Pageable pageable);
}
