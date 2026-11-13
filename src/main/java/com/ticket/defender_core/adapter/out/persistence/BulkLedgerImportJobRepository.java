package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.BulkLedgerImportJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BulkLedgerImportJobRepository extends JpaRepository<BulkLedgerImportJob, UUID> {
    Optional<BulkLedgerImportJob> findByCreatedByMembershipIdAndIdempotencyKeyDigest(UUID membershipId, String digest);
    Optional<BulkLedgerImportJob> findByTenantIdAndSourceSystemAndSourceBatchId(UUID tenantId, String sourceSystem, String sourceBatchId);
    Optional<BulkLedgerImportJob> findByIdAndTenantId(UUID id, UUID tenantId);
    List<BulkLedgerImportJob> findByTenantIdOrderByCreatedAtDescIdDesc(UUID tenantId);
    List<BulkLedgerImportJob> findTop10ByStatusInOrderByUpdatedAtAsc(List<BulkLedgerImportJob.Status> statuses);
}
