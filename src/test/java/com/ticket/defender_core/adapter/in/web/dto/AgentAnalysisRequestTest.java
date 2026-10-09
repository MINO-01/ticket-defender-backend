package com.ticket.defender_core.adapter.in.web.dto;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentAnalysisRequestTest {

    /** 필수 값이 있는 요청은 유효합니다. */
    @Test
    @DisplayName("티켓별 식별자와 64자리 가명 연결 토큰이 있는 요청은 유효하다")
    void request_withRequiredFieldsAndSignalHash_isValid() {
        // given
        AgentAnalysisRequest request = new AgentAnalysisRequest(List.of(
                new AgentAnalysisRequest.TicketData(
                        "uid-1", "res-1", "event-1", "a".repeat(64), null, null, null)
        ));

        // when
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            // then
            assertThat(factory.getValidator().validate(request)).isEmpty();
        }
    }

    /** 연결 신호가 없는 티켓은 요청에서 거부됩니다. */
    @Test
    @DisplayName("가명 분석 신호가 없으면 요청을 거부한다")
    void request_withoutSignalHash_isInvalid() {
        // given
        AgentAnalysisRequest request = new AgentAnalysisRequest(List.of(
                new AgentAnalysisRequest.TicketData("uid-1", "res-1", "event-1", null, null, null, null)
        ));

        // when
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            var violations = factory.getValidator().validate(request);

            // then
            assertThat(violations)
                    .anySatisfy(violation -> assertThat(violation.getMessage())
                            .contains("하나 이상의 가명 분석 신호"));
        }
    }

    /** 같은 공연·예매·계정 조합은 중복으로 받지 않습니다. */
    @Test
    @DisplayName("동일 공연·예매·계정 조합은 요청에 중복으로 포함할 수 없다")
    void request_withDuplicateTicketIdentity_isInvalid() {
        // given
        AgentAnalysisRequest.TicketData ticket = new AgentAnalysisRequest.TicketData(
                "uid-1", "res-1", "event-1", "a".repeat(64), null, null, null);
        AgentAnalysisRequest request = new AgentAnalysisRequest(List.of(ticket, ticket));

        // when
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            var violations = factory.getValidator().validate(request);

            // then
            assertThat(violations)
                    .anySatisfy(violation -> assertThat(violation.getMessage())
                            .contains("동일한 공연·예매·계정 조합"));
        }
    }
}
