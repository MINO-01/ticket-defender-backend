package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.in.web.dto.AdminFanReportDto;
import com.ticket.defender_core.adapter.out.persistence.EvidenceAssetRepository;
import com.ticket.defender_core.adapter.out.persistence.FanReportRepository;
import com.ticket.defender_core.adapter.out.persistence.TenantEventRepository;
import com.ticket.defender_core.domain.AccessRecordStatus;
import com.ticket.defender_core.domain.EvidenceAsset;
import com.ticket.defender_core.domain.FanReport;
import com.ticket.defender_core.security.AdminAccessService;
import com.ticket.defender_core.security.AdminActor;
import com.ticket.defender_core.security.AdminResourceScope;
import com.ticket.defender_core.security.AdminRole;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 관리자 팬 제보 목록과 상세를 저장된 tenant/event 범위 안에서만 조회합니다.
 * 조회 전용이며 분석 실행, Case 생성, 승인, PDF 발급, 원본 이미지 조회를 수행하지 않습니다.
 */
@Service
@RequiredArgsConstructor
public class AdminFanReportQueryService {
    /** 기존 Listing Context 분석 이력 API와 같은 역할입니다. 상세 화면에서 같은 증거의 분석 이력을 이어서 조회합니다. */
    private static final Set<AdminRole> VIEW_ROLES = Set.of(AdminRole.ANALYST, AdminRole.REVIEWER);
    private static final int MAX_PAGE_SIZE = 100;

    private final AdminAccessService access;
    private final TenantEventRepository events;
    private final FanReportRepository reports;
    private final EvidenceAssetRepository assets;

    @Transactional(readOnly = true)
    public AdminFanReportDto.Page list(UUID eventId, FanReport.IntakeStatus status, int page, int pageSize) {
        if (page < 0 || pageSize < 1 || pageSize > MAX_PAGE_SIZE || (long) page * pageSize > Integer.MAX_VALUE)
            throw new CaseApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        // tenant는 클라이언트 값이 아니라 서버가 조회한 공연 소속으로 결정합니다.
        AdminActor actor = access.requireEventAccess(eventId, VIEW_ROLES);
        AdminFanReportDto.Event event = event(actor.tenantId(), actor.eventId());
        var found = reports.findAdminPage(actor.tenantId(), actor.eventId(), status, PageRequest.of(page, pageSize));
        List<UUID> ids = found.stream().map(FanReportRepository.AdminFanReportRow::getFanReportId).toList();
        Map<UUID, EvidenceAssetRepository.EvidenceCount> counts = ids.isEmpty() ? Map.of()
                : assets.countByFanReportIds(ids, EvidenceAsset.StorageStatus.STORED).stream()
                .collect(Collectors.toMap(EvidenceAssetRepository.EvidenceCount::getFanReportId, Function.identity()));
        List<AdminFanReportDto.Summary> items = found.stream().map(row -> {
            var count = counts.get(row.getFanReportId());
            return new AdminFanReportDto.Summary(row.getFanReportId(), row.getReportType(), row.getIntakeStatus(),
                    utc(row.getReceivedAt()), utc(row.getSubmittedPerformanceAt()),
                    count == null ? 0 : count.getEvidenceCount(), count == null ? 0 : count.getStoredEvidenceCount());
        }).toList();
        return new AdminFanReportDto.Page(event, items, page, pageSize, found.getTotalElements(), found.hasNext());
    }

    @Transactional(readOnly = true)
    public AdminFanReportDto.Detail detail(UUID fanReportId) {
        FanReport report = reports.findById(fanReportId).orElseThrow(AdminFanReportQueryService::notFound);
        // 저장된 제보의 tenant/event로만 권한을 확인하고, 범위 밖 제보는 존재 여부를 드러내지 않습니다.
        AdminActor actor = access.requireResourceAccess(
                new AdminResourceScope(report.getTenantId(), report.getEventId()), null, VIEW_ROLES);
        AdminFanReportDto.Event event = event(actor.tenantId(), actor.eventId());
        List<AdminFanReportDto.Evidence> evidence = assets.findByFanReportIdOrderByCreatedAtAscEvidenceIdAsc(
                report.getFanReportId()).stream().map(asset -> evidence(report, asset)).toList();
        return new AdminFanReportDto.Detail(report.getFanReportId(), event, report.getReportType(),
                report.getIntakeStatus(), utc(report.getReceivedAt()), utc(report.getRetentionUntil()),
                utc(report.getSubmittedPerformanceAt()), report.getSeatArea(), report.getDescription(),
                report.getListingUrl(), report.getPlatform(), report.getSeller(), report.getAskingPrice(), evidence);
    }

    /**
     * 증거 저장과 제보 접수가 모두 확정된 경우에만 원본 저장 완료로 표시합니다.
     * 저장 대기, 저장 실패, 접수 미확정 제보의 증거는 해시를 숨겨 정상 원본처럼 보이지 않게 합니다.
     */
    private static AdminFanReportDto.Evidence evidence(FanReport report, EvidenceAsset asset) {
        boolean originalStored = asset.getStorageStatus() == EvidenceAsset.StorageStatus.STORED
                && report.getIntakeStatus() != FanReport.IntakeStatus.PENDING_STORAGE
                && report.getIntakeStatus() != FanReport.IntakeStatus.STORAGE_FAILED;
        return new AdminFanReportDto.Evidence(asset.getEvidenceId(), asset.getMediaType(), asset.getByteSize(),
                asset.getStorageStatus(), originalStored, originalStored ? asset.getSha256() : null,
                utc(asset.getCreatedAt()));
    }

    private AdminFanReportDto.Event event(UUID tenantId, UUID eventId) {
        return events.findById(eventId)
                .filter(item -> tenantId.equals(item.getTenantId())
                        && AccessRecordStatus.ACTIVE.name().equals(item.getStatus()))
                .map(item -> new AdminFanReportDto.Event(item.getId(), item.getExternalEventRef(), item.getName()))
                .orElseThrow(AdminFanReportQueryService::notFound);
    }

    private static OffsetDateTime utc(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static CaseApiException notFound() {
        return new CaseApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }
}
