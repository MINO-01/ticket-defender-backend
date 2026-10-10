package com.ticket.defender_core.adapter.out.api;

/** FastAPI가 요청에 맞지 않는 분석 결과를 반환했을 때 발생합니다. */
public class FastApiResponseContractException extends IllegalStateException {

    public FastApiResponseContractException(String message) {
        super(message);
    }

    public FastApiResponseContractException(String message, Throwable cause) {
        super(message, cause);
    }
}
