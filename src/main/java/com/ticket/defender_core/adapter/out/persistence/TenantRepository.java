package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {
}
