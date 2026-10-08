package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.persistence.ReservationRepository;
import com.ticket.defender_core.adapter.out.persistence.TicketAuditRepository;
import com.ticket.defender_core.domain.Reservation;
import com.ticket.defender_core.domain.TicketAudit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class VlmAnalysisService {

    private final ReservationRepository reservationRepository;
    private final TicketAuditRepository ticketAuditRepository;

    /** reportReceivedAt은 VLM 분석 시작 전, 제보를 서버가 처음 접수한 시각이어야 합니다. */
    public record VlmParsedData(
            String zone,
            String rowNum,
            String seatNum,
            Double mappingScore,
            String reporterId,
            List<String> evidenceImageUrls,
            LocalDateTime reportReceivedAt
    ) {
        public VlmParsedData {
            if (reportReceivedAt == null) {
                throw new IllegalArgumentException("제보 접수 시각은 필수입니다.");
            }
        }
    }

    /**
     * [Track 2] VLM 팬 제보 데이터 대조 및 암표상 적발 로직
     */
    @Transactional
    public void processVlmReport(VlmParsedData parsedData) {

        if (parsedData.mappingScore() == null || parsedData.mappingScore() < 0.85) {
            log.warn("VLM 매핑 신뢰도(Score: {}) 미달. 대조를 건너뜁니다. (제보자: {})",
                    parsedData.mappingScore(), parsedData.reporterId());
            return;
        }

        Optional<Reservation> reservationOpt = reservationRepository.findByZoneAndRowNumAndSeatNum(
                parsedData.zone(),
                parsedData.rowNum(),
                parsedData.seatNum()
        );

        if (reservationOpt.isEmpty()) {
            log.info("제보된 좌석({}구역 {}열 {}번)과 일치하는 예매 내역이 없습니다. (취소표 혹은 허위 제보)",
                    parsedData.zone(), parsedData.rowNum(), parsedData.seatNum());
            return;
        }

        Reservation suspect = reservationOpt.get();

        TicketAudit audit = TicketAudit.createVlmAudit(
                suspect.getReservationNo(),
                suspect.getAccountId(),
                parsedData.mappingScore(),
                parsedData.reporterId() ,
                parsedData.evidenceImageUrls(),
                parsedData.reportReceivedAt()
        );

        ticketAuditRepository.save(audit);

        log.info("[적발 완료] 제보자({})의 증거로 계정({})이 암표상으로 적발되었습니다! (신뢰도: {})",
                parsedData.reporterId(), suspect.getAccountId(), parsedData.mappingScore());
    }
}
