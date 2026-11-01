package com.ticket.defender_core.adapter.out.persistence;

import com.ticket.defender_core.domain.EvidenceAsset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface EvidenceAssetRepository extends JpaRepository<EvidenceAsset, UUID> {
    List<EvidenceAsset> findByFanReportIdOrderByCreatedAtAsc(UUID fanReportId);

    /** 관리자 상세 조회용입니다. 같은 생성 시각의 증거도 ID로 순서를 고정합니다. */
    List<EvidenceAsset> findByFanReportIdOrderByCreatedAtAscEvidenceIdAsc(UUID fanReportId);

    /** 한 페이지의 제보별 증거 수를 한 번에 집계해 목록 조회의 N+1 조회를 피합니다. */
    @Query("""
            select a.fanReportId as fanReportId, count(a) as evidenceCount,
                   sum(case when a.storageStatus = :stored then 1 else 0 end) as storedEvidenceCount
            from EvidenceAsset a
            where a.fanReportId in :reportIds
            group by a.fanReportId
            """)
    List<EvidenceCount> countByFanReportIds(@Param("reportIds") Collection<UUID> reportIds,
            @Param("stored") EvidenceAsset.StorageStatus stored);

    /** 제보별 전체 증거 수와 저장 완료 증거 수입니다. */
    interface EvidenceCount {
        UUID getFanReportId();
        long getEvidenceCount();
        long getStoredEvidenceCount();
    }
}
