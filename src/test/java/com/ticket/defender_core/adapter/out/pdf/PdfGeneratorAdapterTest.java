package com.ticket.defender_core.adapter.out.pdf;

import com.ticket.defender_core.domain.TicketAudit;
import com.ticket.defender_core.domain.AuditStatus;
import com.ticket.defender_core.domain.EvidenceType;
import com.ticket.defender_core.domain.MacroAnalysisEvidence;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.io.IOException;
import java.time.LocalDateTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PdfGeneratorAdapterTest {

    private PdfGeneratorAdapter createAdapter() {
        ClassLoaderTemplateResolver templateResolver = new ClassLoaderTemplateResolver();
        templateResolver.setPrefix("templates/");
        templateResolver.setSuffix(".html");
        templateResolver.setTemplateMode(TemplateMode.HTML);
        templateResolver.setCharacterEncoding("UTF-8");

        SpringTemplateEngine templateEngine = new SpringTemplateEngine();
        templateEngine.setTemplateResolver(templateResolver);

        return new PdfGeneratorAdapter(templateEngine);
    }

    private TicketAudit createDummyAudit() {
        List<String> dummyImages = List.of(
                "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII="
        );
        TicketAudit audit = mock(TicketAudit.class);
        when(audit.getStatus()).thenReturn(AuditStatus.FRAUD_DETECTED);
        when(audit.getEvidenceType()).thenReturn(EvidenceType.FAN_REPORT);
        when(audit.getDetectedAt()).thenReturn(java.time.LocalDateTime.now());
        when(audit.getReservationNo()).thenReturn("RES-TEST-001");
        when(audit.getAccountId()).thenReturn("account-1");
        when(audit.getReporterId()).thenReturn("reporter-1");
        when(audit.getMappingScore()).thenReturn(0.995);
        when(audit.getEvidenceImageUrls()).thenReturn(dummyImages);
        return audit;
    }

    @Test
    void candidateCannotBeRenderedAsOfficialPdf() {
        TicketAudit candidate = TicketAudit.createVlmAudit(
                "res-1", "account-1", 0.95, "reporter-1", List.of("image-1"),
                java.time.LocalDateTime.now());

        assertThatThrownBy(() -> createAdapter().generateVlmReportPdf(candidate))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void macroGraphAuditCannotBeRenderedAsFanReportPdf() {
        TicketAudit macroAudit = TicketAudit.createMacroAudit(new MacroAnalysisEvidence(
                "account-macro", "reservation-macro", "event-macro",
                "a".repeat(64), null, null, null,
                "cluster-1", 0.91, "LOUVAIN", "1.0.0", "request-1",
                LocalDateTime.parse("2026-10-08T10:15:30"),
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("33333333-3333-4333-8333-333333333333")
        ));
        assertThat(macroAudit.getStatus()).isEqualTo(AuditStatus.FRAUD_DETECTED);
        assertThat(macroAudit.getDetectedAt()).isNotNull();
        assertThat(macroAudit.getEvidenceType()).isEqualTo(EvidenceType.MACRO_GRAPH);
        assertThat(macroAudit.getMappingScore()).isNull();

        assertThatThrownBy(() -> createAdapter().generateVlmReportPdf(macroAudit))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("팬 제보 PDF를 렌더링할 수 없는 감사 내역입니다.");
    }

    @Test
    void issuedReportCannotBeRenderedAgain() {
        TicketAudit audit = createDummyAudit();
        when(audit.getStatus()).thenReturn(AuditStatus.REPORT_ISSUED);

        assertThatThrownBy(() -> createAdapter().generateVlmReportPdf(audit))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void auditWithoutDetectionTimeCannotBeRendered() {
        TicketAudit audit = createDummyAudit();
        when(audit.getDetectedAt()).thenReturn(null);

        assertThatThrownBy(() -> createAdapter().generateVlmReportPdf(audit))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void fanReportWithoutMappingScoreHasExplicitError() {
        TicketAudit audit = createDummyAudit();
        when(audit.getMappingScore()).thenReturn(null);

        assertThatThrownBy(() -> createAdapter().generateVlmReportPdf(audit))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("팬 제보 PDF에 유효한 매핑 점수가 없습니다.");
    }

    @Test
    void invalidMappingScoreCannotBeRendered() {
        for (Double score : List.of(Double.NaN, -0.01, 1.01)) {
            TicketAudit audit = createDummyAudit();
            when(audit.getMappingScore()).thenReturn(score);

            assertThatThrownBy(() -> createAdapter().generateVlmReportPdf(audit))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("팬 제보 PDF에 유효한 매핑 점수가 없습니다.");
        }
    }

    @Test
    void fanReportWithoutReporterIdHasExplicitError() {
        TicketAudit audit = createDummyAudit();
        when(audit.getReporterId()).thenReturn(" ");

        assertThatThrownBy(() -> createAdapter().generateVlmReportPdf(audit))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("팬 제보 PDF에 필요한 식별 정보가 없습니다.");
    }

    @Test
    @DisplayName("도메인 객체가 주어지면 정상적인 PDF 바이트 배열을 반환해야 한다")
    void generatePdfSuccessfully() {
        PdfGeneratorAdapter adapter = createAdapter();
        byte[] pdfBytes = adapter.generateVlmReportPdf(createDummyAudit());

        assertThat(pdfBytes).isNotNull();
        assertThat(pdfBytes.length).isGreaterThan(0);

        String pdfHeader = new String(pdfBytes, 0, 5);
        assertThat(pdfHeader).isEqualTo("%PDF-");
    }

    @Test
    @Disabled("수동 시각적 검증용 테스트이므로 평소 CI/CD 빌드 시에는 제외합니다. 눈으로 볼 때만 직접 실행하세요.")
    @DisplayName("생성된 PDF를 로컬 파일로 저장하여 폰트와 레이아웃을 확인한다")
    void generatePdfAndSaveToLocalFile() throws IOException {
        PdfGeneratorAdapter adapter = createAdapter();
        byte[] pdfBytes = adapter.generateVlmReportPdf(createDummyAudit());

        Path path = Paths.get("test-report-visual-check.pdf");
        Files.write(path, pdfBytes);

        System.out.println("PDF를 성공적으로 생성하였습니다. 프로젝트 최상단 폴더를 확인해 보세요");
        System.out.println("경로: " + path.toAbsolutePath());
    }
}
