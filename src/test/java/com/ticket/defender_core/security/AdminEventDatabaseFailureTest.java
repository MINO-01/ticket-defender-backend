package com.ticket.defender_core.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ticket.defender_core.adapter.out.persistence.AdminMembershipRepository;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 멤버십 조회 장애를 정상 빈 목록으로 바꾸지 않고 503으로 응답하는지 검증합니다. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:admin-event-db-failure;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class AdminEventDatabaseFailureTest {
    @Autowired private MockMvc mvc;

    @MockitoBean(name = "adminJwtDecoder")
    private JwtDecoder adminDecoder;

    @MockitoBean
    private AdminMembershipRepository memberships;

    @Test
    void membershipLookupFailureIsReportedAs503NotAnEmptyList() throws Exception {
        given(adminDecoder.decode(anyString())).willAnswer(call -> Jwt.withTokenValue(call.getArgument(0))
                .header("alg", "RS256").subject("analyst-a").issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300)).build());
        given(memberships.findActiveBySubject(anyString(), any()))
                .willThrow(new DataAccessResourceFailureException("synthetic database outage"));

        mvc.perform(get("/api/v1/admin/events").header(HttpHeaders.AUTHORIZATION, "Bearer synthetic"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.items").doesNotExist());
    }
}
