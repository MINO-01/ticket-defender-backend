package com.ticket.defender_core.adapter.out.api;

/** Track 1 내부 인증 비밀 설정이 누락되었거나 HTTP 헤더 규칙 또는 최소 길이를 만족하지 않을 때 발생합니다. */
public class FastApiAuthenticationConfigurationException extends IllegalStateException {

    public FastApiAuthenticationConfigurationException() {
        super("Track 1 internal authentication configuration is invalid.");
    }
}
