package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.pdf.PdfGeneratorAdapter;
import com.ticket.defender_core.adapter.out.persistence.TicketAuditRepository;
import com.ticket.defender_core.domain.AuditStatus;
import com.ticket.defender_core.domain.EvidenceType;
import com.ticket.defender_core.domain.TicketAudit;
import com.ticket.defender_core.domain.event.DuplicateReportEvent;
import com.ticket.defender_core.domain.event.FraudVerifiedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
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

    /** 적발 내역의 소명 보고서를 PDF로 발급합니다. */
    @Transactional
    public byte[] issueAuditReport(Long auditId) {

        TicketAudit audit = ticketAuditRepository.findById(auditId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 감사 내역입니다. ID: " + auditId));

        validateFirstReporter(audit, auditId);

        byte[] pdf = pdfGeneratorAdapter.generateVlmReportPdf(audit);

        int updated = ticketAuditRepository.updateStatusById(
                auditId,
                AuditStatus.REPORT_ISSUED,
                AuditStatus.FRAUD_DETECTED
        );

        if (updated == 0) {
            throw new IllegalStateException("보고서 발급 조건(FRAUD_DETECTED 상태)을 충족하지 않거나 이미 발급된 건입니다.");
        }

        log.info("적발 내역 상태를 REPORT_ISSUED로 변경했습니다. 예매 번호: {}", audit.getReservationNo());
        
        rejectDuplicateReports(audit, auditId);

        eventPublisher.publishEvent(new FraudVerifiedEvent(
                audit.getId(),
                audit.getReservationNo(),
                audit.getReporterId()
        ));
        log.info("메인 제보 승인 이벤트를 발행했습니다. 감사 내역 ID: {}", audit.getId());

        return pdf;
    }

    /** 먼저 접수된 팬 제보만 보고서를 발급할 수 있습니다. */
    private void validateFirstReporter(TicketAudit requestedAudit, Long auditId) {
        if (requestedAudit.getEvidenceType() != EvidenceType.FAN_REPORT) {
            return;
        }

        String reservationNo = requestedAudit.getReservationNo();
        if (reservationNo == null || reservationNo.isBlank()) {
            throw new IllegalStateException("예매 번호가 없어 최초 제보 여부를 확인할 수 없습니다.");
        }

        TicketAudit firstReport = ticketAuditRepository
                .findEarliestReportByReservationNoAndEvidenceType(
                        reservationNo,
                        EvidenceType.FAN_REPORT,
                        PageRequest.of(0, 1)
                )
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "예매 건의 제보 내역을 찾을 수 없습니다. 예매 번호: " + reservationNo
                ));

        if (!auditId.equals(firstReport.getId())) {
            throw new IllegalStateException(
                    "가장 먼저 접수된 유효 제보가 아니므로 보고서를 발급할 수 없습니다. 예매 번호: " + reservationNo
            );
        }
    }

    /** 같은 예매 번호의 후순위 팬 제보를 반려합니다. */
    private void rejectDuplicateReports(TicketAudit approvedAudit, Long auditId) {
        String reservationNo = approvedAudit.getReservationNo();
        if (reservationNo == null || reservationNo.isBlank()) {
            return;
        }

        List<TicketAudit> duplicateAudits = ticketAuditRepository.findByReservationNoAndIdNotAndEvidenceTypeAndStatus(
                reservationNo,
                auditId,
                EvidenceType.FAN_REPORT,
                AuditStatus.FRAUD_DETECTED
        );
        if (duplicateAudits.isEmpty()) {
            return;
        }

        int rejectedCount = ticketAuditRepository.rejectDuplicateAudits(
                reservationNo,
                auditId,
                EvidenceType.FAN_REPORT,
                AuditStatus.DUPLICATED,
                AuditStatus.FRAUD_DETECTED
        );

        if (rejectedCount != duplicateAudits.size()) {
            throw new IllegalStateException(
                    "중복 제보 상태가 처리 중 변경되었습니다. 예매 번호: " + reservationNo
            );
        }

        duplicateAudits.forEach(duplicateAudit -> eventPublisher.publishEvent(new DuplicateReportEvent(
                duplicateAudit.getId(),
                reservationNo,
                duplicateAudit.getReporterId()
        )));

        log.info("후순위 중복 제보 {}건을 반려했습니다. 예매 번호: {}", rejectedCount, reservationNo);
    }
}
