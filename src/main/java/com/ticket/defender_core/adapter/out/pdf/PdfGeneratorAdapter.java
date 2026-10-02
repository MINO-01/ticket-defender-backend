package com.ticket.defender_core.adapter.out.pdf;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.ticket.defender_core.domain.TicketAudit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

@Slf4j
@Component
@RequiredArgsConstructor
public class PdfGeneratorAdapter {

    private final TemplateEngine templateEngine;

    /**
     * TicketAudit 도메인 객체를 받아 암표 탐지 보고서 PDF 바이트 배열로 변환합니다.
     */
    public byte[] generateVlmReportPdf(TicketAudit audit) {

        Context context = new Context();
        context.setVariable("reservationNo", audit.getReservationNo());
        context.setVariable("accountId", audit.getAccountId());
        context.setVariable("reporterId", audit.getReporterId());
        context.setVariable("mappingScore", String.format("%.2f", audit.getMappingScore() * 100));
        context.setVariable("detectedAt", audit.getDetectedAt().toString());
        context.setVariable("evidenceImageUrls", audit.getEvidenceImageUrls());

        String htmlContent = templateEngine.process("vlm-report", context);

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {

            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();

            builder.useFont(() -> {
                try {
                    return new ClassPathResource("fonts/NanumGothic.ttf").getInputStream();
                } catch (java.io.IOException e) {
                    log.error("한글 폰트 파일을 읽어오는 데 실패했습니다.", e);
                    throw new RuntimeException("폰트 로드 실패", e);
                }
            }, "NanumGothic");

            builder.withHtmlContent(htmlContent, null);
            builder.toStream(outputStream);
            builder.run();

            log.info("PDF 리포트 생성 완료 (예약번호: {})", audit.getReservationNo());
            return outputStream.toByteArray();

        } catch (Exception e) {
            log.error("PDF 렌더링 중 오류 발생 (예약번호: {})", audit.getReservationNo(), e);
            throw new RuntimeException("PDF 리포트 생성에 실패했습니다.", e);
        }
    }
}