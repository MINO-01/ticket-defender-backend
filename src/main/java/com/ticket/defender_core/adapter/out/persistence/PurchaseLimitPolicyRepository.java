package com.ticket.defender_core.adapter.out.persistence;
import com.ticket.defender_core.domain.PurchaseLimitPolicy; import jakarta.persistence.LockModeType; import org.springframework.data.jpa.repository.*; import org.springframework.data.repository.query.Param; import java.util.*;
public interface PurchaseLimitPolicyRepository extends JpaRepository<PurchaseLimitPolicy, UUID> {
    List<PurchaseLimitPolicy> findByTenantIdAndEventIdOrderByPerformanceIdAsc(UUID tenantId, UUID eventId);
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select p from PurchaseLimitPolicy p where p.id=:id and p.tenantId=:tenantId and p.eventId=:eventId") Optional<PurchaseLimitPolicy> lockByIdAndScope(@Param("id") UUID id,@Param("tenantId") UUID tenantId,@Param("eventId") UUID eventId);
    Optional<PurchaseLimitPolicy> findByTenantIdAndEventIdAndActivePerformanceId(UUID tenantId, UUID eventId, UUID performanceId);
    boolean existsByTenantIdAndEventIdAndPerformanceIdAndPolicyVersion(UUID tenantId, UUID eventId, UUID performanceId, String policyVersion);
}
