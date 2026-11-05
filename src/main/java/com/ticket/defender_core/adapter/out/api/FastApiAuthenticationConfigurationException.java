package com.ticket.defender_core.adapter.out.api;

/** Track 1 인증 비밀 또는 인증 헤더를 보낼 FastAPI 전송 주소 설정이 유효하지 않을 때 발생합니다. */
public class FastApiAuthenticationConfigurationException extends IllegalStateException {

    public FastApiAuthenticationConfigurationException() {
        super("Track 1 internal authentication configuration is invalid.");
    }
}
