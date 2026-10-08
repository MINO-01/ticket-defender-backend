package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.pdf.PdfGeneratorAdapter;
import com.ticket.defender_core.adapter.out.persistence.TicketAuditRepository;
import com.ticket.defender_core.domain.AuditStatus;
import com.ticket.defender_core.domain.EvidenceType;
import com.ticket.defender_core.domain.TicketAudit;
import com.ticket.defender_core.domain.event.DuplicateReportEvent;
import com.ticket.defender_core.domain.event.FraudVerifiedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditReportServiceTest {

    private static final Long MAIN_AUDIT_ID = 1L;
    private static final String RESERVATION_NO = "RES-1234";

    @InjectMocks
    private AuditReportService auditReportService;

    @Mock
    private TicketAuditRepository ticketAuditRepository;

    @Mock
    private PdfGeneratorAdapter pdfGeneratorAdapter;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Test
    @DisplayName("메인 제보 승인 후 동일 예매 건의 후순위 제보를 반려하고 제보자별 이벤트를 발행한다.")
    void issueAuditReport_RejectsAndNotifiesDuplicateReporters() {
        // given
        TicketAudit mainAudit = mainAudit();
        TicketAudit duplicateOne = duplicateAudit(2L, "reporter_one");
        TicketAudit duplicateTwo = duplicateAudit(3L, "reporter_two");
        byte[] expectedPdf = {1, 2, 3};

        given(ticketAuditRepository.findById(MAIN_AUDIT_ID)).willReturn(Optional.of(mainAudit));
        given(ticketAuditRepository.findEarliestReportByReservationNoAndEvidenceType(
                RESERVATION_NO,
                EvidenceType.FAN_REPORT,
                PageRequest.of(0, 1)
        )).willReturn(List.of(mainAudit));
        given(pdfGeneratorAdapter.generateVlmReportPdf(mainAudit)).willReturn(expectedPdf);
        given(ticketAuditRepository.updateStatusById(
                MAIN_AUDIT_ID,
                AuditStatus.REPORT_ISSUED,
                AuditStatus.FRAUD_DETECTED
        )).willReturn(1);
        given(ticketAuditRepository.findByReservationNoAndIdNotAndStatus(
                RESERVATION_NO,
                MAIN_AUDIT_ID,
                AuditStatus.FRAUD_DETECTED
        )).willReturn(List.of(duplicateOne, duplicateTwo));
        given(ticketAuditRepository.rejectDuplicateAudits(
                RESERVATION_NO,
                MAIN_AUDIT_ID,
                AuditStatus.DUPLICATED,
                AuditStatus.FRAUD_DETECTED
        )).willReturn(2);

        // when
        byte[] result = auditReportService.issueAuditReport(MAIN_AUDIT_ID);

        // then
        assertThat(result).containsExactly((byte) 1, (byte) 2, (byte) 3);
        verify(ticketAuditRepository).updateStatusById(
                MAIN_AUDIT_ID,
                AuditStatus.REPORT_ISSUED,
                AuditStatus.FRAUD_DETECTED
        );
        verify(ticketAuditRepository).rejectDuplicateAudits(
                RESERVATION_NO,
                MAIN_AUDIT_ID,
                AuditStatus.DUPLICATED,
                AuditStatus.FRAUD_DETECTED
        );
        verify(eventPublisher).publishEvent(new DuplicateReportEvent(2L, RESERVATION_NO, "reporter_one"));
        verify(eventPublisher).publishEvent(new DuplicateReportEvent(3L, RESERVATION_NO, "reporter_two"));
        verify(eventPublisher).publishEvent(
                new FraudVerifiedEvent(MAIN_AUDIT_ID, RESERVATION_NO, "main_reporter")
        );
        verifyNoMoreInteractions(eventPublisher);
    }

    @Test
    @DisplayName("후순위 제보가 없어도 메인 보고서를 정상적으로 발급한다.")
    void issueAuditReport_CompletesWhenThereAreNoDuplicates() {
        // given
        TicketAudit mainAudit = mainAudit();
        byte[] expectedPdf = {4, 5, 6};

        given(ticketAuditRepository.findById(MAIN_AUDIT_ID)).willReturn(Optional.of(mainAudit));
        given(ticketAuditRepository.findEarliestReportByReservationNoAndEvidenceType(
                RESERVATION_NO,
                EvidenceType.FAN_REPORT,
                PageRequest.of(0, 1)
        )).willReturn(List.of(mainAudit));
        given(pdfGeneratorAdapter.generateVlmReportPdf(mainAudit)).willReturn(expectedPdf);
        given(ticketAuditRepository.updateStatusById(
                MAIN_AUDIT_ID,
                AuditStatus.REPORT_ISSUED,
                AuditStatus.FRAUD_DETECTED
        )).willReturn(1);
        given(ticketAuditRepository.findByReservationNoAndIdNotAndStatus(
                RESERVATION_NO,
                MAIN_AUDIT_ID,
                AuditStatus.FRAUD_DETECTED
        )).willReturn(List.of());

        // when
        byte[] result = auditReportService.issueAuditReport(MAIN_AUDIT_ID);

        // then
        assertThat(result).containsExactly((byte) 4, (byte) 5, (byte) 6);
        verify(ticketAuditRepository, never()).rejectDuplicateAudits(
                anyString(), anyLong(), any(AuditStatus.class), any(AuditStatus.class)
        );
        verify(eventPublisher).publishEvent(
                new FraudVerifiedEvent(MAIN_AUDIT_ID, RESERVATION_NO, "main_reporter")
        );
        verify(eventPublisher, never()).publishEvent(isA(DuplicateReportEvent.class));
        verifyNoMoreInteractions(eventPublisher);
    }

    @Test
    @DisplayName("최초 접수 제보가 아니면 PDF를 만들거나 제보 상태를 변경하지 않는다.")
    void issueAuditReport_RejectsLaterReportBeforeGeneratingPdf() {
        // given
        Long laterAuditId = 2L;
        TicketAudit laterAudit = mock(TicketAudit.class);
        TicketAudit firstAudit = mock(TicketAudit.class);
        given(laterAudit.getEvidenceType()).willReturn(EvidenceType.FAN_REPORT);
        given(laterAudit.getReservationNo()).willReturn(RESERVATION_NO);
        given(firstAudit.getId()).willReturn(MAIN_AUDIT_ID);
        given(ticketAuditRepository.findById(laterAuditId)).willReturn(Optional.of(laterAudit));
        given(ticketAuditRepository.findEarliestReportByReservationNoAndEvidenceType(
                RESERVATION_NO,
                EvidenceType.FAN_REPORT,
                PageRequest.of(0, 1)
        )).willReturn(List.of(firstAudit));

        // when
        Throwable thrown = catchThrowable(() -> auditReportService.issueAuditReport(laterAuditId));

        // then
        assertThat(thrown)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("가장 먼저 접수된 유효 제보가 아니므로");
        verifyNoInteractions(pdfGeneratorAdapter);
        verify(ticketAuditRepository, never()).updateStatusById(
                anyLong(), any(AuditStatus.class), any(AuditStatus.class)
        );
        verify(ticketAuditRepository, never()).rejectDuplicateAudits(
                anyString(), anyLong(), any(AuditStatus.class), any(AuditStatus.class)
        );
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("PDF 생성에 실패하면 상태 변경, 중복 반려, 이벤트 발행을 수행하지 않는다.")
    void issueAuditReport_DoesNotRejectDuplicatesWhenPdfGenerationFails() {
        // given
        TicketAudit mainAudit = mock(TicketAudit.class);
        TicketAudit firstAudit = mock(TicketAudit.class);
        given(mainAudit.getEvidenceType()).willReturn(EvidenceType.FAN_REPORT);
        given(mainAudit.getReservationNo()).willReturn(RESERVATION_NO);
        given(firstAudit.getId()).willReturn(MAIN_AUDIT_ID);
        given(ticketAuditRepository.findById(MAIN_AUDIT_ID)).willReturn(Optional.of(mainAudit));
        given(ticketAuditRepository.findEarliestReportByReservationNoAndEvidenceType(
                RESERVATION_NO,
                EvidenceType.FAN_REPORT,
                PageRequest.of(0, 1)
        )).willReturn(List.of(firstAudit));
        given(pdfGeneratorAdapter.generateVlmReportPdf(mainAudit))
                .willThrow(new IllegalStateException("PDF 생성 실패"));

        // when
        Throwable thrown = catchThrowable(() -> auditReportService.issueAuditReport(MAIN_AUDIT_ID));

        // then
        assertThat(thrown)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PDF 생성 실패");
        verify(ticketAuditRepository, never()).updateStatusById(
                anyLong(), any(AuditStatus.class), any(AuditStatus.class)
        );
        verify(ticketAuditRepository, never()).findByReservationNoAndIdNotAndStatus(
                anyString(), anyLong(), any(AuditStatus.class)
        );
        verify(ticketAuditRepository, never()).rejectDuplicateAudits(
                anyString(), anyLong(), any(AuditStatus.class), any(AuditStatus.class)
        );
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("메인 제보 상태 변경에 실패하면 후순위 제보를 반려하지 않는다.")
    void issueAuditReport_DoesNotRejectDuplicatesWhenMainStatusUpdateFails() {
        // given
        TicketAudit mainAudit = mock(TicketAudit.class);
        TicketAudit firstAudit = mock(TicketAudit.class);
        given(mainAudit.getEvidenceType()).willReturn(EvidenceType.FAN_REPORT);
        given(mainAudit.getReservationNo()).willReturn(RESERVATION_NO);
        given(firstAudit.getId()).willReturn(MAIN_AUDIT_ID);
        given(ticketAuditRepository.findById(MAIN_AUDIT_ID)).willReturn(Optional.of(mainAudit));
        given(ticketAuditRepository.findEarliestReportByReservationNoAndEvidenceType(
                RESERVATION_NO,
                EvidenceType.FAN_REPORT,
                PageRequest.of(0, 1)
        )).willReturn(List.of(firstAudit));
        given(pdfGeneratorAdapter.generateVlmReportPdf(mainAudit)).willReturn(new byte[]{8});
        given(ticketAuditRepository.updateStatusById(
                MAIN_AUDIT_ID,
                AuditStatus.REPORT_ISSUED,
                AuditStatus.FRAUD_DETECTED
        )).willReturn(0);

        // when
        Throwable thrown = catchThrowable(() -> auditReportService.issueAuditReport(MAIN_AUDIT_ID));

        // then
        assertThat(thrown)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("보고서 발급 조건");
        verify(ticketAuditRepository, never()).findByReservationNoAndIdNotAndStatus(
                anyString(), anyLong(), any(AuditStatus.class)
        );
        verify(ticketAuditRepository, never()).rejectDuplicateAudits(
                anyString(), anyLong(), any(AuditStatus.class), any(AuditStatus.class)
        );
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("조회한 후순위 제보 수와 실제 반려 수가 다르면 이벤트를 발행하지 않고 실패한다.")
    void issueAuditReport_FailsWhenDuplicateCountChangesConcurrently() {
        // given
        TicketAudit mainAudit = mock(TicketAudit.class);
        TicketAudit firstAudit = mock(TicketAudit.class);
        given(mainAudit.getReservationNo()).willReturn(RESERVATION_NO);
        given(mainAudit.getEvidenceType()).willReturn(EvidenceType.FAN_REPORT);
        given(firstAudit.getId()).willReturn(MAIN_AUDIT_ID);
        TicketAudit duplicateAudit = mock(TicketAudit.class);

        given(ticketAuditRepository.findById(MAIN_AUDIT_ID)).willReturn(Optional.of(mainAudit));
        given(ticketAuditRepository.findEarliestReportByReservationNoAndEvidenceType(
                RESERVATION_NO,
                EvidenceType.FAN_REPORT,
                PageRequest.of(0, 1)
        )).willReturn(List.of(firstAudit));
        given(pdfGeneratorAdapter.generateVlmReportPdf(mainAudit)).willReturn(new byte[]{7});
        given(ticketAuditRepository.updateStatusById(
                MAIN_AUDIT_ID,
                AuditStatus.REPORT_ISSUED,
                AuditStatus.FRAUD_DETECTED
        )).willReturn(1);
        given(ticketAuditRepository.findByReservationNoAndIdNotAndStatus(
                RESERVATION_NO,
                MAIN_AUDIT_ID,
                AuditStatus.FRAUD_DETECTED
        )).willReturn(List.of(duplicateAudit));
        given(ticketAuditRepository.rejectDuplicateAudits(
                RESERVATION_NO,
                MAIN_AUDIT_ID,
                AuditStatus.DUPLICATED,
                AuditStatus.FRAUD_DETECTED
        )).willReturn(0);

        // when
        Throwable thrown = catchThrowable(() -> auditReportService.issueAuditReport(MAIN_AUDIT_ID));

        // then
        assertThat(thrown)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("중복 제보 상태가 처리 중 변경되었습니다");
        verify(ticketAuditRepository).rejectDuplicateAudits(
                RESERVATION_NO,
                MAIN_AUDIT_ID,
                AuditStatus.DUPLICATED,
                AuditStatus.FRAUD_DETECTED
        );
        verifyNoInteractions(eventPublisher);
    }

    private TicketAudit mainAudit() {
        TicketAudit audit = mock(TicketAudit.class);
        given(audit.getId()).willReturn(MAIN_AUDIT_ID);
        given(audit.getReservationNo()).willReturn(RESERVATION_NO);
        given(audit.getReporterId()).willReturn("main_reporter");
        given(audit.getEvidenceType()).willReturn(EvidenceType.FAN_REPORT);
        return audit;
    }

    private TicketAudit duplicateAudit(Long id, String reporterId) {
        TicketAudit audit = mock(TicketAudit.class);
        given(audit.getId()).willReturn(id);
        given(audit.getReporterId()).willReturn(reporterId);
        return audit;
    }
}
