package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.UUID;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select t from Tenant t where t.id = :id")
    Optional<Tenant> lockById(UUID id);
}
