package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.persistence.ReservationRepository;
import com.ticket.defender_core.adapter.out.persistence.TicketAuditRepository;
import com.ticket.defender_core.domain.Reservation;
import com.ticket.defender_core.domain.TicketAudit;
import com.ticket.defender_core.security.EventScopeLookup;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class VlmAnalysisService {

    private final ReservationRepository reservationRepository;
    private final TicketAuditRepository ticketAuditRepository;
    private final EventScopeLookup eventScopeLookup;

    public enum ExtractionStatus { COMPLETED, FAILED }

    /** 제보의 저장된 고객사·공연 범위와 예매처가 부여한 회차 식별자입니다. VLM 출력에서 만들지 않습니다. */
    public record ReportScope(UUID tenantId, UUID eventId, String performanceRef) {}

    /** reportReceivedAt은 VLM 분석 시작 전, 제보를 서버가 처음 접수한 시각이어야 합니다. */
    public record VlmParsedData(
            String zone,
            String rowNum,
            String seatNum,
            Double mappingScore,
            String reporterId,
            List<String> evidenceImageUrls,
            LocalDateTime reportReceivedAt,
            ExtractionStatus extractionStatus
    ) {
        public VlmParsedData(String zone, String rowNum, String seatNum, Double mappingScore,
                String reporterId, List<String> evidenceImageUrls, LocalDateTime reportReceivedAt) {
            this(zone, rowNum, seatNum, mappingScore, reporterId, evidenceImageUrls,
                    reportReceivedAt, ExtractionStatus.COMPLETED);
        }

        public VlmParsedData {
            if (reportReceivedAt == null) {
                throw new IllegalArgumentException("제보 접수 시각은 필수입니다.");
            }
            Objects.requireNonNull(extractionStatus, "VLM 추출 상태는 필수입니다.");
        }
    }

    /** 기존 범위 없는 호출은 조회 없이 확인불가로 처리합니다. */
    public void processVlmReport(VlmParsedData parsedData) {
        processVlmReport(parsedData, null);
    }

    /** 검증된 제보 범위에서만 좌석 후보를 조회하며, 결과는 부정판매 판정이 아닙니다. */
    public VlmVerificationStatus processVlmReport(VlmParsedData parsedData, ReportScope reportScope) {
        Objects.requireNonNull(parsedData, "VLM 추출 결과는 필수입니다.");
        String requestId = UUID.randomUUID().toString();

        if (parsedData.extractionStatus() == ExtractionStatus.FAILED) {
            return logStatus(requestId, VlmVerificationStatus.EXTRACTION_FAILED);
        }
        if (!hasUsableSeat(parsedData) || !hasCompleteScope(reportScope)) {
            return logStatus(requestId, VlmVerificationStatus.UNVERIFIABLE);
        }

        List<Reservation> candidates;
        try {
            UUID storedTenantId = eventScopeLookup.findTenantId(reportScope.eventId()).orElse(null);
            if (!reportScope.tenantId().equals(storedTenantId)) {
                return logStatus(requestId, VlmVerificationStatus.UNVERIFIABLE);
            }
            candidates = reservationRepository
                    .findTop2ByTenantIdAndEventIdAndPerformanceRefAndZoneAndRowNumAndSeatNum(
                            reportScope.tenantId(), reportScope.eventId(), reportScope.performanceRef(),
                            parsedData.zone(), parsedData.rowNum(), parsedData.seatNum());
        } catch (DataAccessException lookupFailure) {
            return logStatus(requestId, VlmVerificationStatus.LOOKUP_FAILED);
        }

        if (candidates.isEmpty()) {
            return logStatus(requestId, VlmVerificationStatus.NO_MATCH);
        }
        if (candidates.size() != 1) {
            return logStatus(requestId, VlmVerificationStatus.UNVERIFIABLE);
        }

        Reservation candidate = candidates.getFirst();
        TicketAudit audit = TicketAudit.createVlmAudit(
                candidate.getReservationNo(),
                candidate.getAccountId(),
                parsedData.mappingScore(),
                parsedData.reporterId(),
                parsedData.evidenceImageUrls(),
                parsedData.reportReceivedAt()
        );
        ticketAuditRepository.save(audit);
        return logStatus(requestId, VlmVerificationStatus.MATCH_CANDIDATE);
    }

    private boolean hasUsableSeat(VlmParsedData data) {
        return data.mappingScore() != null && Double.isFinite(data.mappingScore())
                && data.mappingScore() >= 0.85 && data.mappingScore() <= 1.0
                && !isBlank(data.zone()) && !isBlank(data.rowNum()) && !isBlank(data.seatNum());
    }

    private boolean hasCompleteScope(ReportScope scope) {
        return scope != null && scope.tenantId() != null && scope.eventId() != null
                && !isBlank(scope.performanceRef());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private VlmVerificationStatus logStatus(String requestId, VlmVerificationStatus status) {
        if (status == VlmVerificationStatus.LOOKUP_FAILED || status == VlmVerificationStatus.EXTRACTION_FAILED) {
            log.warn("Track 2 예매 후보 대조. 요청 ID: {}, 상태: {}", requestId, status);
        } else {
            log.info("Track 2 예매 후보 대조. 요청 ID: {}, 상태: {}", requestId, status);
        }
        return status;
    }
}
