package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.OfficialPdfSyntheticTenant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** 합성 격리 고객사 등록부는 조회만 애플리케이션 경로에서 사용하며, 등록은 격리 환경 준비 절차가 담당합니다. */
public interface OfficialPdfSyntheticTenantRepository extends JpaRepository<OfficialPdfSyntheticTenant, UUID> {
}
