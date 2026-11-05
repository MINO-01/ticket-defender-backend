package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.PerformanceVerificationAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PerformanceVerificationAuditRepository extends JpaRepository<PerformanceVerificationAudit, UUID> {
    List<PerformanceVerificationAudit> findByPerformanceIdOrderByRevisionAsc(UUID performanceId);
}
