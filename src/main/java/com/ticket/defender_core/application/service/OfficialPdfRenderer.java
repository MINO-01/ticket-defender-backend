package com.ticket.defender_core.application.service;

import com.openhtmltopdf.pdfboxout.PDFCreationListener;
import com.openhtmltopdf.pdfboxout.PdfBoxRenderer;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.ticket.defender_core.domain.ApprovalDecision;
import com.ticket.defender_core.domain.ApprovalRequest;
import com.ticket.defender_core.domain.EvidenceSnapshot;
import com.ticket.defender_core.domain.OfficialPdfIssuance;
import com.ticket.defender_core.domain.ReportVersion;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Renders only a server-owned XHTML template and escaped sealed report fields. */
@Component
@RequiredArgsConstructor
public class OfficialPdfRenderer {
    private static final DateTimeFormatter KOREAN_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'KST'");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z0-9]+)}}");
    private final ObjectMapper json;

    public byte[] render(RenderContext context) {
        if (context == null) throw new OfficialPdfRenderingException("Official PDF render context is missing");
        OfficialPdfTemplateRegistry.Template template = context.template();
        OfficialPdfIssuance issuance = context.issuance();
        ReportVersion report = context.report();
        ApprovalDecision decision = context.decision();
        ApprovalRequest request = context.request();
        EvidenceSnapshot snapshot = context.snapshot();
        if (template == null || issuance == null || report == null || decision == null || request == null
                || snapshot == null
                || issuance.getRequestedAt() == null || context.evidenceSourceCount() < 0
                || !template.version().equals(issuance.getOfficialTemplateVersion())
                || !template.sha256().equals(issuance.getOfficialTemplateSha256())
                || !issuance.getApprovalDecisionId().equals(decision.getId())
                || !decision.getApprovalRequestId().equals(request.getId())
                || !issuance.getReportVersionId().equals(report.getId())
                || !issuance.getEvidenceSnapshotId().equals(snapshot.getId())
                || issuance.getReportRevision() != report.getRevision()
                || issuance.getEvidenceRevision() != snapshot.getEvidenceRevision()
                || decision.getOutcome() != ApprovalDecision.Outcome.APPROVE) {
            throw new OfficialPdfRenderingException("Official PDF template binding is invalid");
        }
        rejectExternalResources(template.artifact());
        Map<String, Object> content = parse(report.getContentJson());
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("issuanceId", issuance.getId().toString());
        // This immutable request time keeps recovery renders deterministic for the server-owned object key.
        variables.put("requestedAt", issuance.getRequestedAt().atOffset(ZoneOffset.UTC).toString());
        variables.put("requestedAtKorea", issuance.getRequestedAt().atOffset(ZoneOffset.UTC)
                .atZoneSameInstant(ZoneId.of("Asia/Seoul")).format(KOREAN_TIME));
        variables.put("caseId", issuance.getCaseId().toString());
        variables.put("reportVersionId", issuance.getReportVersionId().toString());
        // Pseudonymous membership IDs only; names and contact details are not part of the sealed record.
        variables.put("requesterMembershipId", request.getRequestedByMembershipId().toString());
        variables.put("approverMembershipId", decision.getDecidedByMembershipId().toString());
        variables.put("approvalDecisionId", issuance.getApprovalDecisionId().toString());
        variables.put("approvalOutcome", decision.getOutcome().name());
        variables.put("approvalDecidedAt", decision.getDecidedAt().atOffset(ZoneOffset.UTC).toString());
        variables.put("reportRevision", Long.toString(issuance.getReportRevision()));
        variables.put("reportContentSha256", issuance.getReportContentSha256());
        variables.put("evidenceSnapshotId", snapshot.getId().toString());
        variables.put("evidenceRevision", Long.toString(issuance.getEvidenceRevision()));
        variables.put("manifestSha256", issuance.getManifestSha256());
        variables.put("evidenceSourceCount", Integer.toString(context.evidenceSourceCount()));
        variables.put("sourceIntegrity", snapshot.getSourceIntegrity());
        variables.put("verificationSummary", context.evidenceSourceCount() == 0
                ? "고정 manifest에 연결된 증거 출처가 없습니다. 별도 증거 보존 적격성은 정책에 따릅니다."
                : "W06 SHA-256 무결성 및 W07 Object Lock 및 retention 적격성을 발급 경계에서 재검증했습니다.");
        variables.put("officialTemplateVersion", issuance.getOfficialTemplateVersion());
        variables.put("officialTemplateSha256", issuance.getOfficialTemplateSha256());
        variables.put("title", content(content, "title", 160));
        variables.put("assessment", content(content, "assessment", 6000));
        variables.put("limitations", content(content, "limitations", 3000));
        variables.put("conclusion", content(content, "conclusion", 3000));
        String xhtml = fill(template.artifact(), variables);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(); PDDocument document = new PDDocument()) {
            // PDFBox and openhtmltopdf otherwise use the clock for /ID and CreationDate; recovering the
            // deterministic object key requires the same sealed input to produce the same bytes.
            document.setDocumentId(issuance.getId().getMostSignificantBits() ^ issuance.getId().getLeastSignificantBits());
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.usePDDocument(document);
            builder.useFastMode();
            builder.useFont(() -> font(), "NanumGothic");
            // There are no links, images, stylesheets, or URI placeholders in this fixed artifact.
            builder.withHtmlContent(xhtml, null);
            builder.toStream(out);
            Calendar fixedCreation = GregorianCalendar.from(issuance.getRequestedAt().atZone(ZoneOffset.UTC));
            try (PdfBoxRenderer pdf = builder.buildPdfRenderer()) {
                pdf.setListener(new PDFCreationListener() {
                    @Override public void preOpen(PdfBoxRenderer renderer) { }
                    @Override public void preWrite(PdfBoxRenderer renderer, int pageCount) { }
                    // Runs after openhtmltopdf stamps the clock and immediately before save.
                    @Override public void onClose(PdfBoxRenderer renderer) {
                        renderer.getPdfDocument().getDocumentInformation().setCreationDate(fixedCreation);
                    }
                });
                pdf.layout();
                pdf.createPDF();
            }
            byte[] bytes = out.toByteArray();
            if (bytes.length < 5 || bytes[0] != '%' || bytes[1] != 'P' || bytes[2] != 'D'
                    || bytes[3] != 'F' || bytes[4] != '-') throw new OfficialPdfRenderingException("Renderer did not produce a PDF");
            return bytes;
        } catch (OfficialPdfRenderingException known) {
            throw known;
        } catch (Exception failure) {
            throw new OfficialPdfRenderingException("Official PDF rendering failed", failure);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String value) {
        try {
            Object parsed = json.readValue(value, Map.class);
            if (!(parsed instanceof Map<?, ?> map)) throw new OfficialPdfRenderingException("Report content is malformed");
            return (Map<String, Object>) map;
        } catch (RuntimeException malformed) {
            throw new OfficialPdfRenderingException("Report content is malformed", malformed);
        }
    }

    private static String content(Map<String, Object> content, String field, int maximum) {
        Object value = content.get(field);
        if (!(value instanceof String text) || text.isBlank() || text.length() > maximum)
            throw new OfficialPdfRenderingException("Report content is incomplete");
        return text;
    }

    /**
     * Scans only the server-owned template once, so sealed reviewer text containing braces or another
     * placeholder name is never rescanned or substituted.
     */
    static String fill(String artifact, Map<String, String> variables) {
        Matcher placeholder = PLACEHOLDER.matcher(artifact);
        StringBuilder xhtml = new StringBuilder(artifact.length());
        while (placeholder.find()) {
            String value = variables.get(placeholder.group(1));
            if (value == null) throw new OfficialPdfRenderingException("Official PDF template has unresolved fields");
            // XML-safe escaping: HTML named entities such as &middot; are undefined in XHTML.
            placeholder.appendReplacement(xhtml, Matcher.quoteReplacement(HtmlUtils.htmlEscape(value, "UTF-8")));
        }
        placeholder.appendTail(xhtml);
        return xhtml.toString();
    }

    private static void rejectExternalResources(String artifact) {
        String normalized = artifact.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("<img") || normalized.contains("<iframe") || normalized.contains("<object")
                || normalized.contains("<embed") || normalized.contains("href=") || normalized.contains("src=")
                || normalized.contains("url(") || normalized.contains("@import")) {
            throw new OfficialPdfRenderingException("Official PDF template has external resources");
        }
    }

    private static java.io.InputStream font() {
        try {
            return new ClassPathResource("fonts/NanumGothic.ttf").getInputStream();
        } catch (IOException missing) {
            throw new OfficialPdfRenderingException("Korean PDF font is unavailable", missing);
        }
    }

    public static class OfficialPdfRenderingException extends RuntimeException {
        public OfficialPdfRenderingException(String message) { super(message); }
        public OfficialPdfRenderingException(String message, Throwable cause) { super(message, cause); }
    }

    public record RenderContext(OfficialPdfTemplateRegistry.Template template, OfficialPdfIssuance issuance,
                                ReportVersion report, ApprovalDecision decision, ApprovalRequest request,
                                EvidenceSnapshot snapshot, int evidenceSourceCount) { }
}
