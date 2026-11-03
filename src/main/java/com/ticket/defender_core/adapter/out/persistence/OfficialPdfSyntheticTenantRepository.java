package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.OfficialPdfSyntheticTenant;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/** 합성 격리 고객사 등록부는 조회만 애플리케이션 경로에서 사용하며, 등록은 격리 환경 준비 절차가 담당합니다. */
public interface OfficialPdfSyntheticTenantRepository extends JpaRepository<OfficialPdfSyntheticTenant, UUID> {
    /**
     * 공유 잠금(MySQL {@code FOR SHARE})으로 등록 행을 읽습니다. 쓰기 트랜잭션이 끝날 때까지 등록 해제(DELETE)는
     * 대기하고, 확정 전 해제가 진행 중이면 이 조회가 대기한 뒤 최신 확정 상태를 봅니다. 여러 발급은 서로 막지 않습니다.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select t from OfficialPdfSyntheticTenant t where t.tenantId = :tenantId")
    Optional<OfficialPdfSyntheticTenant> findByTenantIdForShare(@Param("tenantId") UUID tenantId);
}
