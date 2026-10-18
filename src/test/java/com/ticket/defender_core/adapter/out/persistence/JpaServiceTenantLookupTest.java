package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.AccessRecordStatus;
import com.ticket.defender_core.domain.ServiceTenantBinding;
import com.ticket.defender_core.domain.Tenant;
import com.ticket.defender_core.security.ServiceTenantLookup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import(JpaServiceTenantLookup.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:service-tenant-lookup;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"
})
class JpaServiceTenantLookupTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String ISSUER = "https://issuer.example.test/service";

    @Autowired private TenantRepository tenants;
    @Autowired private ServiceTenantBindingRepository bindings;
    @Autowired private ServiceTenantLookup lookup;

    @Test
    void onlyExactActiveIssuerClientAndSubjectMappingResolvesTenant() {
        tenants.save(new Tenant(TENANT, "고객사", AccessRecordStatus.ACTIVE));
        bindings.save(new ServiceTenantBinding(UUID.randomUUID(), ISSUER,
                "trusted-ingest", "ingest-1", TENANT, AccessRecordStatus.ACTIVE));

        assertThat(lookup.findActiveTenantId(ISSUER, "trusted-ingest", "ingest-1")).contains(TENANT);
        assertThat(lookup.findActiveTenantId(ISSUER, "other-client", "ingest-1")).isEmpty();
        assertThat(lookup.findActiveTenantId("https://other.example.test", "trusted-ingest", "ingest-1"))
                .isEmpty();
        assertThat(lookup.findActiveTenantId(ISSUER, "trusted-ingest", "other-subject")).isEmpty();
    }

    @Test
    void inactiveTenantAndBindingAreUnavailable() {
        tenants.save(new Tenant(TENANT, "고객사", AccessRecordStatus.INACTIVE));
        bindings.save(new ServiceTenantBinding(UUID.randomUUID(), ISSUER,
                "trusted-ingest", "ingest-1", TENANT, AccessRecordStatus.ACTIVE));
        assertThat(lookup.findActiveTenantId(ISSUER, "trusted-ingest", "ingest-1")).isEmpty();

        UUID activeTenant = UUID.fromString("22222222-2222-4222-8222-222222222222");
        tenants.save(new Tenant(activeTenant, "활성 고객사", AccessRecordStatus.ACTIVE));
        bindings.save(new ServiceTenantBinding(UUID.randomUUID(), ISSUER,
                "trusted-ingest", "ingest-2", activeTenant, AccessRecordStatus.INACTIVE));
        assertThat(lookup.findActiveTenantId(ISSUER, "trusted-ingest", "ingest-2")).isEmpty();
    }

    @Test
    void oneVerifiedServiceIdentityCannotMapToTwoTenants() {
        tenants.save(new Tenant(TENANT, "고객사", AccessRecordStatus.ACTIVE));
        UUID otherTenant = UUID.fromString("22222222-2222-4222-8222-222222222222");
        tenants.save(new Tenant(otherTenant, "다른 고객사", AccessRecordStatus.ACTIVE));
        bindings.saveAndFlush(new ServiceTenantBinding(UUID.randomUUID(), ISSUER,
                "trusted-ingest", "ingest-1", TENANT, AccessRecordStatus.ACTIVE));

        assertThatThrownBy(() -> bindings.saveAndFlush(new ServiceTenantBinding(UUID.randomUUID(), ISSUER,
                "trusted-ingest", "ingest-1", otherTenant, AccessRecordStatus.ACTIVE)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
