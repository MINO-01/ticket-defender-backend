package com.ticket.defender_core.application.service;

/** Track 1 분석이 정상 완료됐는지, 분석 서버 장애로 보류됐는지를 구분합니다. */
public enum FraudAnalysisStatus {
    COMPLETED,
    UNAVAILABLE
}
