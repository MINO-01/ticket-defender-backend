package com.ticket.defender_core.adapter.in.web.dto;

import com.ticket.defender_core.domain.EvidenceAsset;
import com.ticket.defender_core.domain.FanReport;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 관리자 팬 제보 조회 응답입니다. JPA 엔티티 대신 화면에 필요한 값만 담으며
 * 객체 저장 키, 객체 버전, 접수번호, 복구 비밀 digest, 멱등성 digest, tenantId는 포함하지 않습니다.
 */
public final class AdminFanReportDto {
    private AdminFanReportDto() {
    }

    public record Event(UUID id, String externalEventId, String name) {
    }

    /** 목록 항목입니다. 제보 본문과 판매자 정보는 상세 조회에서만 반환합니다. */
    public record Summary(
            UUID fanReportId,
            FanReport.ReportType reportType,
            FanReport.IntakeStatus intakeStatus,
            OffsetDateTime receivedAt,
            OffsetDateTime submittedPerformanceAt,
            long evidenceCount,
            long storedEvidenceCount
    ) {
    }

    public record Page(
            Event event,
            List<Summary> items,
            int page,
            int pageSize,
            long totalItems,
            boolean hasNext
    ) {
    }

    /**
     * 증거 메타데이터입니다. originalStored가 false이면 원본 저장이 확정되지 않은 증거이므로 sha256을 반환하지 않습니다.
     * 원본 이미지 다운로드와 미리보기 주소는 별도 보안 계약 전까지 제공하지 않습니다.
     */
    public record Evidence(
            UUID evidenceId,
            String mediaType,
            long byteSize,
            EvidenceAsset.StorageStatus storageStatus,
            boolean originalStored,
            String sha256,
            OffsetDateTime createdAt
    ) {
    }

    /** 상세 응답입니다. listingUrl은 팬이 입력한 문자열이며 서버가 접속하거나 검증하지 않았습니다. */
    public record Detail(
            UUID fanReportId,
            Event event,
            FanReport.ReportType reportType,
            FanReport.IntakeStatus intakeStatus,
            OffsetDateTime receivedAt,
            OffsetDateTime retentionUntil,
            OffsetDateTime submittedPerformanceAt,
            String seatArea,
            String description,
            String listingUrl,
            String platform,
            String seller,
            BigDecimal askingPrice,
            List<Evidence> evidence
    ) {
    }
}
