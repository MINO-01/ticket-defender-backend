package com.ticket.defender_core.adapter.out.persistence;
import com.ticket.defender_core.domain.PurchaseLimitPolicyAudit; import org.springframework.data.jpa.repository.JpaRepository; import java.util.UUID;
public interface PurchaseLimitPolicyAuditRepository extends JpaRepository<PurchaseLimitPolicyAudit, UUID> { }
