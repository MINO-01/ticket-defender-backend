package com.ticket.defender_core.application.service;

/** Track 2 추출·예매 후보 대조 결과이며 부정거래 판정이 아닙니다. */
public enum VlmVerificationStatus {
    MATCH_CANDIDATE,
    NO_MATCH,
    UNVERIFIABLE,
    LOOKUP_FAILED,
    EXTRACTION_FAILED
}
