package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.ReservationImportError;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReservationImportErrorRepository extends JpaRepository<ReservationImportError, UUID> {
    List<ReservationImportError> findByBatchIdOrderByRowIndexAscCodeAsc(UUID batchId);
}
