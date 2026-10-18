package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.ServiceTenantBinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ServiceTenantBindingRepository extends JpaRepository<ServiceTenantBinding, UUID> {
    Optional<ServiceTenantBinding> findByIssuerAndClientIdAndSubject(String issuer, String clientId, String subject);
}
