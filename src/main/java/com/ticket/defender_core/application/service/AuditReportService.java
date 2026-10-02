package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.pdf.PdfGeneratorAdapter;
import com.ticket.defender_core.domain.AuditStatus;
import com.ticket.defender_core.domain.TicketAudit;
import com.ticket.defender_core.domain.event.FraudVerifiedEvent;
import com.ticket.defender_core.adapter.out.persistence.TicketAuditRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditReportService {

    private final TicketAuditRepository ticketAuditRepository;
    private final PdfGeneratorAdapter pdfGeneratorAdapter;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 특정 적발 내역(auditId)에 대해 암표 탐지 보고서를 발급하고 상태를 확정합니다.
     */
    @Transactional
    public byte[] issueAuditReport(Long auditId) {

        TicketAudit audit = ticketAuditRepository.findById(auditId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 감사 내역입니다. ID: " + auditId));

        byte[] pdf = pdfGeneratorAdapter.generateVlmReportPdf(audit);

        int updated = ticketAuditRepository.bulkUpdateStatusToReportIssued(
                List.of(audit.getReservationNo()),
                com.ticket.defender_core.domain.AuditStatus.REPORT_ISSUED,
                com.ticket.defender_core.domain.AuditStatus.FRAUD_DETECTED
        );

        if (updated == 0) {
            throw new IllegalStateException("보고서 발급 조건(FRAUD_DETECTED 상태)을 만족하지 않거나 이미 발급된 건입니다.");
        }

        log.info("적발 내역 상태 업데이트 완료 (REPORT_ISSUED) - ReservationNo: {}", audit.getReservationNo());

        // 4. 안전하게 디커플링된 알림 이벤트 발행
        eventPublisher.publishEvent(new FraudVerifiedEvent(
                audit.getId(),
                audit.getReservationNo(),
                audit.getReporterId()
        ));
        log.info("FraudVerifiedEvent 이벤트 발행 완료 - ReporterId: {}", audit.getReporterId());

        return pdf;
    }
}