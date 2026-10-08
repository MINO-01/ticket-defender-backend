package com.ticket.defender_core.adapter.in.web.dto;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentAnalysisRequestTest {

    @Test
    @DisplayName("티켓별 식별자와 64자리 가명 연결 토큰이 있는 요청은 유효하다")
    void request_withRequiredFieldsAndSignalHash_isValid() {
        // given: 필수 티켓 식별자와 유효한 가명 연결 토큰을 준비합니다.
        AgentAnalysisRequest request = new AgentAnalysisRequest(List.of(
                new AgentAnalysisRequest.TicketData(
                        "uid-1", "res-1", "event-1", "a".repeat(64), null, null, null)
        ));

        // when: Bean Validation으로 요청 DTO를 검증합니다.
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            // then: 요청 필드 제약을 모두 만족합니다.
            assertThat(factory.getValidator().validate(request)).isEmpty();
        }
    }

    @Test
    @DisplayName("가명 분석 신호가 없으면 요청을 거부한다")
    void request_withoutSignalHash_isInvalid() {
        // given: 계정·예매·공연 식별자는 있지만 연결 신호는 없는 티켓을 준비합니다.
        AgentAnalysisRequest request = new AgentAnalysisRequest(List.of(
                new AgentAnalysisRequest.TicketData("uid-1", "res-1", "event-1", null, null, null, null)
        ));

        // when: Bean Validation으로 요청 DTO를 검증합니다.
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            var violations = factory.getValidator().validate(request);

            // then: 하나 이상의 가명 연결 신호가 필요하다는 제약에 걸립니다.
            assertThat(violations)
                    .anySatisfy(violation -> assertThat(violation.getMessage())
                            .contains("하나 이상의 가명 분석 신호"));
        }
    }

    @Test
    @DisplayName("동일 공연·예매·계정 조합은 요청에 중복으로 포함할 수 없다")
    void request_withDuplicateTicketIdentity_isInvalid() {
        // given: 같은 티켓 식별자를 두 번 포함한 요청을 준비합니다.
        AgentAnalysisRequest.TicketData ticket = new AgentAnalysisRequest.TicketData(
                "uid-1", "res-1", "event-1", "a".repeat(64), null, null, null);
        AgentAnalysisRequest request = new AgentAnalysisRequest(List.of(ticket, ticket));

        // when: Bean Validation으로 요청 DTO를 검증합니다.
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            var violations = factory.getValidator().validate(request);

            // then: 중복된 티켓 식별 조합을 거부합니다.
            assertThat(violations)
                    .anySatisfy(violation -> assertThat(violation.getMessage())
                            .contains("동일한 공연·예매·계정 조합"));
        }
    }
}
