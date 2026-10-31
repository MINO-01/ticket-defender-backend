package com.ticket.defender_core.application.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OfficialPdfRendererFillTest {
    @Test
    void sealedContentWithBracesIsRenderedVerbatimInsteadOfFailingIssuance() {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("title", "제목 {{ 표기");
        variables.put("conclusion", "결론");

        String xhtml = OfficialPdfRenderer.fill("<p>{{title}}</p><p>{{conclusion}}</p>", variables);

        assertThat(xhtml).isEqualTo("<p>제목 {{ 표기</p><p>결론</p>");
    }

    @Test
    void placeholderNamesInsideSealedContentAreNotSubstitutedByLaterFields() {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("title", "{{conclusion}}");
        variables.put("conclusion", "승인된 결론");

        String xhtml = OfficialPdfRenderer.fill("<p>{{title}}</p><p>{{conclusion}}</p>", variables);

        assertThat(xhtml).isEqualTo("<p>{{conclusion}}</p><p>승인된 결론</p>");
    }

    @Test
    void valuesAreXmlEscapedAndUnknownTemplateFieldsStillFail() {
        assertThat(OfficialPdfRenderer.fill("<p>{{title}}</p>", Map.of("title", "<b>&\"")))
                .isEqualTo("<p>&lt;b&gt;&amp;&quot;</p>");
        assertThatThrownBy(() -> OfficialPdfRenderer.fill("<p>{{missing}}</p>", Map.of("title", "x")))
                .isInstanceOf(OfficialPdfRenderer.OfficialPdfRenderingException.class);
    }
}
