package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.Track1PurchaseLedgerError;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface Track1PurchaseLedgerErrorRepository extends JpaRepository<Track1PurchaseLedgerError, UUID> {
    List<Track1PurchaseLedgerError> findByBatchIdIn(Collection<UUID> batchIds);
}
