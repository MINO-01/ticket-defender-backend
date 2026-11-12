package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.BulkLedgerImportError;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BulkLedgerImportErrorRepository extends JpaRepository<BulkLedgerImportError, UUID> {
    List<BulkLedgerImportError> findByJobIdOrderBySequenceNoAsc(UUID jobId);
}
