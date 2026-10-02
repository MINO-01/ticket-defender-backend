package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.pdf.PdfGeneratorAdapter;
import com.ticket.defender_core.adapter.out.persistence.TicketAuditRepository;
import com.ticket.defender_core.domain.AuditStatus;
import com.ticket.defender_core.domain.TicketAudit;
import com.ticket.defender_core.domain.event.FraudVerifiedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditReportServiceTest {

    @InjectMocks
    private AuditReportService auditReportService;

    @Mock
    private TicketAuditRepository ticketAuditRepository;
    @Mock
    private PdfGeneratorAdapter pdfGeneratorAdapter;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Test
    @DisplayName("PDF 발급 요청 시, DB 상태가 업데이트되고 알림 이벤트가 정상적으로 발행되어야 한다")
    void issueAuditReport_PublishesEventAndUpdatesStatus() {
        // given
        Long auditId = 1L;
        TicketAudit dummyAudit = TicketAudit.createVlmAudit(
                "RES-1234", "hacker", 0.99, "hero_fan", List.of()
        );

        when(ticketAuditRepository.findById(auditId)).thenReturn(Optional.of(dummyAudit));
        when(pdfGeneratorAdapter.generateVlmReportPdf(dummyAudit)).thenReturn(new byte[]{1, 2, 3});

        when(ticketAuditRepository.updateStatusById(
                auditId,
                AuditStatus.REPORT_ISSUED,
                AuditStatus.FRAUD_DETECTED
        )).thenReturn(1);

        // when
        byte[] result = auditReportService.issueAuditReport(auditId);

        // then
        assertThat(result).isNotEmpty();

        verify(ticketAuditRepository).updateStatusById(
                auditId,
                AuditStatus.REPORT_ISSUED,
                AuditStatus.FRAUD_DETECTED
        );

        ArgumentCaptor<FraudVerifiedEvent> eventCaptor = ArgumentCaptor.forClass(FraudVerifiedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());

        FraudVerifiedEvent publishedEvent = eventCaptor.getValue();
        assertThat(publishedEvent.reservationNo()).isEqualTo("RES-1234");
        assertThat(publishedEvent.reporterId()).isEqualTo("hero_fan");
    }
}