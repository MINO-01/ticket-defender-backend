package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.TenantEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.UUID;

public interface TenantEventRepository extends JpaRepository<TenantEvent, UUID> {
    Optional<TenantEvent> findByTenantIdAndExternalEventRef(UUID tenantId, String externalEventRef);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select e from TenantEvent e where e.id = :id")
    Optional<TenantEvent> lockById(UUID id);

    /**
     * 한 멤버십에 공연 권한이 부여된 활성 공연만 조회합니다. 권한 행은 같은 tenant 값까지 일치해야 하며,
     * exists 조건이라 같은 공연이 중복 반환되지 않습니다. 이름, ID 순으로 정렬합니다.
     */
    @Query(value = """
            select e from TenantEvent e
            where e.tenantId = :tenantId and e.status = 'ACTIVE'
              and exists (select g.id from MembershipEventGrant g
                          where g.membershipId = :membershipId and g.tenantId = :tenantId and g.eventId = e.id)
            order by e.name asc, e.id asc
            """, countQuery = """
            select count(e) from TenantEvent e
            where e.tenantId = :tenantId and e.status = 'ACTIVE'
              and exists (select g.id from MembershipEventGrant g
                          where g.membershipId = :membershipId and g.tenantId = :tenantId and g.eventId = e.id)
            """)
    Page<TenantEvent> findGrantedActiveEvents(@Param("tenantId") UUID tenantId,
            @Param("membershipId") UUID membershipId, Pageable page);
}
