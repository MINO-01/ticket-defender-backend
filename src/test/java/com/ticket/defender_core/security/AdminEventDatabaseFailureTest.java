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
import org.springframework.transaction.CannotCreateTransactionException;

import com.ticket.defender_core.adapter.out.persistence.AdminMembershipRepository;

import java.sql.SQLTransientConnectionException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 멤버십 조회 장애와 트랜잭션 시작 단계의 DB 연결 실패를 정상 빈 목록으로 바꾸지 않고 503으로 응답하는지 검증합니다.
 * DB 장애가 아닌 일반 코드 결함은 503으로 바꾸지 않습니다.
 */
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
        authenticate();
        given(memberships.findActiveBySubject(anyString(), any()))
                .willThrow(new DataAccessResourceFailureException("synthetic database outage"));

        mvc.perform(get("/api/v1/admin/events").header(HttpHeaders.AUTHORIZATION, "Bearer synthetic"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.items").doesNotExist());
    }

    @Test
    void transactionStartFailureIsReportedAs503WithoutConnectionDetails() throws Exception {
        authenticate();
        given(memberships.findActiveBySubject(anyString(), any())).willThrow(new CannotCreateTransactionException(
                "Could not open JPA EntityManager for transaction",
                new SQLTransientConnectionException("jdbc:mysql://10.0.0.9:3306/secret_db user=root")));

        mvc.perform(get("/api/v1/admin/events").header(HttpHeaders.AUTHORIZATION, "Bearer synthetic"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.items").doesNotExist())
                .andExpect(content().string(not(containsString("10.0.0.9"))))
                .andExpect(content().string(not(containsString("secret_db"))))
                .andExpect(content().string(not(containsString("JPA EntityManager"))));
    }

    @Test
    void unrelatedServerDefectIsNotRelabelledAsDependencyUnavailable() {
        authenticate();
        given(memberships.findActiveBySubject(anyString(), any()))
                .willThrow(new IllegalStateException("synthetic code defect"));

        // 처리되지 않은 일반 결함은 기본 500 처리로 넘어가야 하며 503으로 바뀌면 안 됩니다.
        assertThatThrownBy(() -> mvc.perform(get("/api/v1/admin/events")
                .header(HttpHeaders.AUTHORIZATION, "Bearer synthetic")))
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    private void authenticate() {
        given(adminDecoder.decode(anyString())).willAnswer(call -> Jwt.withTokenValue(call.getArgument(0))
                .header("alg", "RS256").subject("analyst-a").issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300)).build());
    }
}
