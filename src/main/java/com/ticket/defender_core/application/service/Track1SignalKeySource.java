package com.ticket.defender_core.application.service;

/**
 * 운영 환경에서 KMS 또는 HSM이 제공하는 Track 1 신호 HMAC 키의 연결 지점입니다.
 * 연락처와 계좌번호는 값의 범위가 좁아 키가 유출되면 대입으로 원본을 찾을 수 있으므로, 운영 키는 이 인터페이스의
 * 구현을 통해서만 받습니다. 이 저장소는 구현을 제공하지 않으며, 구현이 없으면 v3 원본 수신은 실패로 닫힙니다.
 * 키 버전을 바꾸면 이전 버전의 토큰과는 연결되지 않습니다.
 */
public interface Track1SignalKeySource {
    /** 32자 이상의 키 원본입니다. 반환값을 기록하거나 노출하지 않습니다. */
    String masterKey();

    /** 스테이징 행과 신호 행에 남는 키 버전입니다. 기존 예매 참조 HMAC 키 버전과 같아야 합니다. */
    String keyVersion();
}
