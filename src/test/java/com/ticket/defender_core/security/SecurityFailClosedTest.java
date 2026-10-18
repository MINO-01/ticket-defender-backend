package com.ticket.defender_core.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Runs the production filter chains without a test decoder or authentication provider. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:security-default;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "ticket-defender.security.admin.issuer=",
        "ticket-defender.security.admin.jwk-set-uri=",
        "ticket-defender.security.admin.audience=",
        "ticket-defender.security.analysis.issuer=",
        "ticket-defender.security.analysis.jwk-set-uri=",
        "ticket-defender.security.analysis.audience=",
        "ticket-defender.security.analysis.client-id="
})
@AutoConfigureMockMvc
class SecurityFailClosedTest {
    @Autowired
    private MockMvc mvc;

    @Test
    void unconfiguredAdminAndAnalysisIssuersRejectBearerTokens() throws Exception {
        mvc.perform(post("/api/v1/audits/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer fabricated-token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/audits/123/report")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer fabricated-token"))
                .andExpect(status().isUnauthorized());
    }
}
