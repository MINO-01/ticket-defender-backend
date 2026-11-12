package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.BulkLedgerImportScope;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface BulkLedgerImportScopeRepository extends JpaRepository<BulkLedgerImportScope, UUID> {
    List<BulkLedgerImportScope> findByJobIdOrderByEventIdAscPerformanceIdAsc(UUID jobId);
    List<BulkLedgerImportScope> findByJobIdInOrderByEventIdAscPerformanceIdAsc(Collection<UUID> jobIds);
}
