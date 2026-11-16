package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.BulkLedgerImportJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BulkLedgerImportJobRepository extends JpaRepository<BulkLedgerImportJob, UUID> {
    Optional<BulkLedgerImportJob> findByCreatedByMembershipIdAndIdempotencyKeyDigest(UUID membershipId, String digest);
    Optional<BulkLedgerImportJob> findByTenantIdAndSourceSystemAndSourceBatchId(UUID tenantId, String sourceSystem, String sourceBatchId);
    Optional<BulkLedgerImportJob> findByIdAndTenantId(UUID id, UUID tenantId);
    List<BulkLedgerImportJob> findByTenantIdOrderByCreatedAtDescIdDesc(UUID tenantId);
    List<BulkLedgerImportJob> findTop10ByStatusInOrderByUpdatedAtAsc(List<BulkLedgerImportJob.Status> statuses);

    @Query(value = """
            select j.* from td_bulk_import_jobs j
            left join td_bulk_ledger_candidate_imports c on c.import_id=j.id
            where j.status='READY_TO_PUBLISH' and j.schema_version='v2'
              and (c.id is null or c.status='PREPARING')
            order by coalesce(c.updated_at,j.updated_at),j.id limit 10
            """, nativeQuery = true)
    List<BulkLedgerImportJob> findTop10CandidatePreparationPending();
}
