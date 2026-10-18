package com.ticket.defender_core.application.service;

import com.ticket.defender_core.adapter.out.pdf.PdfGeneratorAdapter;
import com.ticket.defender_core.adapter.out.persistence.TicketAuditRepository;
import com.ticket.defender_core.domain.EvidenceType;
import com.ticket.defender_core.domain.MacroAnalysisEvidence;
import com.ticket.defender_core.domain.TicketAudit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AuditReportServiceTest {

    private static final LocalDateTime RECEIVED_AT = LocalDateTime.parse("2026-10-08T10:15:30");

    @InjectMocks
    private AuditReportService auditReportService;

    @Mock
    private TicketAuditRepository ticketAuditRepository;

    @Mock
    private PdfGeneratorAdapter pdfGeneratorAdapter;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Test
    @DisplayName("점수가 있는 FAN_REPORT도 공식 PDF 발급 전에 차단한다")
    void issueAuditReport_blocksFanReportWithMappingScore() {
        TicketAudit audit = fanAudit(0.95);
        assertThat(audit.getEvidenceType()).isEqualTo(EvidenceType.FAN_REPORT);
        assertThat(audit.getMappingScore()).isEqualTo(0.95);
        lenient().when(ticketAuditRepository.findById(1L)).thenReturn(Optional.of(audit));

        assertIssuanceBlocked(1L);
    }

    @Test
    @DisplayName("점수가 없는 FAN_REPORT도 PDF 생성에 도달하지 않는다")
    void issueAuditReport_blocksFanReportWithNullMappingScore() {
        TicketAudit audit = fanAudit(null);
        assertThat(audit.getMappingScore()).isNull();
        lenient().when(ticketAuditRepository.findById(2L)).thenReturn(Optional.of(audit));

        assertIssuanceBlocked(2L);
    }

    @Test
    @DisplayName("mappingScore가 null인 MACRO_GRAPH도 PDF 생성에 도달하지 않는다")
    void issueAuditReport_blocksMacroReportWithNullMappingScore() {
        TicketAudit audit = TicketAudit.createMacroAudit(new MacroAnalysisEvidence(
                "account-macro", "reservation-macro", "event-macro",
                "a".repeat(64), null, null, null,
                "cluster-1", 0.91, "LOUVAIN", "1.0.0", "request-1", RECEIVED_AT,
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("33333333-3333-4333-8333-333333333333")
        ));
        assertThat(audit.getEvidenceType()).isEqualTo(EvidenceType.MACRO_GRAPH);
        assertThat(audit.getMappingScore()).isNull();
        lenient().when(ticketAuditRepository.findById(3L)).thenReturn(Optional.of(audit));

        assertIssuanceBlocked(3L);
    }

    @Test
    @DisplayName("후순위 FAN_REPORT도 최초 제보 조회 전에 동일하게 차단한다")
    void issueAuditReport_blocksLaterFanReportBeforeFirstReporterLookup() {
        TicketAudit audit = fanAudit(0.99);
        lenient().when(ticketAuditRepository.findById(4L)).thenReturn(Optional.of(audit));

        assertIssuanceBlocked(4L);
    }

    @Test
    @DisplayName("존재하지 않는 감사 ID도 조회 없이 동일하게 차단한다")
    void issueAuditReport_blocksNonexistentAuditIdWithoutLookup() {
        assertIssuanceBlocked(999L);
    }

    @Test
    @DisplayName("null 감사 ID의 서비스 직접 호출도 조회 없이 차단한다")
    void issueAuditReport_blocksNullAuditIdWithoutLookup() {
        assertIssuanceBlocked(null);
    }

    private TicketAudit fanAudit(Double mappingScore) {
        return TicketAudit.createVlmAudit(
                "reservation-fan", "account-fan", mappingScore,
                "reporter-fan", List.of("evidence.png"), RECEIVED_AT
        );
    }

    private void assertIssuanceBlocked(Long auditId) {
        assertThatThrownBy(() -> auditReportService.issueAuditReport(auditId))
                .isInstanceOf(AuditReportIssuanceDisabledException.class)
                .hasMessage("감사 보고서 발급 기능은 승인 절차 구축 전까지 일시적으로 제공되지 않습니다.");
        verifyNoInteractions(ticketAuditRepository, pdfGeneratorAdapter, eventPublisher);
    }
}
