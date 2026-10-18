package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.AccessRecordStatus;
import com.ticket.defender_core.domain.ServiceTenantBinding;
import com.ticket.defender_core.security.ServiceTenantLookup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class JpaServiceTenantLookup implements ServiceTenantLookup {
    private final ServiceTenantBindingRepository bindings;
    private final TenantRepository tenants;

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> findActiveTenantId(String issuer, String clientId, String subject) {
        return bindings.findByIssuerAndClientIdAndSubject(issuer, clientId, subject)
                .filter(binding -> AccessRecordStatus.ACTIVE.name().equals(binding.getStatus()))
                .map(ServiceTenantBinding::getTenantId)
                .filter(tenantId -> tenants.findById(tenantId)
                        .filter(tenant -> AccessRecordStatus.ACTIVE.name().equals(tenant.getStatus()))
                        .isPresent());
    }
}
