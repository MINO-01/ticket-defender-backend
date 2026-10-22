package com.ticket.defender_core.application.service;

import org.springframework.http.HttpStatus;

public class CaseApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public CaseApiException(HttpStatus status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
}
