package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.SyntheticReservationImportTenant;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface SyntheticReservationImportTenantRepository
        extends JpaRepository<SyntheticReservationImportTenant, UUID> {
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select t from SyntheticReservationImportTenant t where t.tenantId = :tenantId")
    Optional<SyntheticReservationImportTenant> findByTenantIdForShare(@Param("tenantId") UUID tenantId);
}
