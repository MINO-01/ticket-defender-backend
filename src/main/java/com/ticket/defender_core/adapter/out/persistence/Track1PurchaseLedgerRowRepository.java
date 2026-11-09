package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.Track1PurchaseLedgerRow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface Track1PurchaseLedgerRowRepository extends JpaRepository<Track1PurchaseLedgerRow, UUID> {
    List<Track1PurchaseLedgerRow> findByBatchIdOrderBySourceRowIndexAsc(UUID batchId);
}
