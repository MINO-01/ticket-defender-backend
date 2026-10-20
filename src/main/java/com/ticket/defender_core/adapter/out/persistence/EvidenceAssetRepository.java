package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.EvidenceAsset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EvidenceAssetRepository extends JpaRepository<EvidenceAsset, UUID> {
    List<EvidenceAsset> findByFanReportIdOrderByCreatedAtAsc(UUID fanReportId);
}
