package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.ReportVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReportVersionRepository extends JpaRepository<ReportVersion, UUID> {
    List<ReportVersion> findByCaseIdOrderByRevisionDesc(UUID caseId);
    Page<ReportVersion> findByCaseId(UUID caseId, Pageable pageable);
    Optional<ReportVersion> findTopByCaseIdOrderByRevisionDesc(UUID caseId);

    @Query("select coalesce(max(r.revision), 0) from ReportVersion r where r.caseId = :caseId")
    long latestRevision(UUID caseId);
}
