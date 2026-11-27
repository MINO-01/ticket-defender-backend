package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.BulkLedgerImportSupplierScope;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BulkLedgerImportSupplierScopeRepository extends JpaRepository<BulkLedgerImportSupplierScope, UUID> {
    List<BulkLedgerImportSupplierScope> findByJobIdOrderBySourceEventCodeAscSourcePerformanceCodeAsc(UUID jobId);
    List<BulkLedgerImportSupplierScope> findByJobIdInOrderByJobIdAscSourceEventCodeAscSourcePerformanceCodeAsc(List<UUID> jobIds);
}
