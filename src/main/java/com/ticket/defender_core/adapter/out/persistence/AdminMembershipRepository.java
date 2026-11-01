package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.AdminMembershipEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdminMembershipRepository extends JpaRepository<AdminMembershipEntity, UUID> {
    Optional<AdminMembershipEntity> findBySubjectAndTenantId(String subject, UUID tenantId);

    /**
     * 검증된 JWT subject의 활성 멤버십 중 활성 고객사 소속만 조회합니다.
     * 호출자는 고객사 수 판단에 필요한 만큼만 limit으로 제한합니다.
     */
    @Query("""
            select m from AdminMembershipEntity m
            where m.subject = :subject and m.status = 'ACTIVE'
              and exists (select t.id from Tenant t where t.id = m.tenantId and t.status = 'ACTIVE')
            order by m.tenantId asc, m.id asc
            """)
    List<AdminMembershipEntity> findActiveBySubject(@Param("subject") String subject, Pageable limit);
}
