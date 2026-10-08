package com.ticket.defender_core.adapter.out.pdf;

import com.ticket.defender_core.domain.TicketAudit;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
        return TicketAudit.createVlmAudit(
                "RES-TEST-001", "hacker_999", 0.995, "hero_fan_01", dummyImages, java.time.LocalDateTime.now()
        );
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
