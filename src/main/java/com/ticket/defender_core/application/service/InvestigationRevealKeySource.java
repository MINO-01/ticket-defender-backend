package com.ticket.defender_core.application.service;

import java.util.UUID;

/** 운영 KMS/HSM 연동 지점. 키는 테넌트별 데이터 키이며 호출자는 키 내용을 기록하면 안 됩니다. */
public interface InvestigationRevealKeySource {
    String currentKeyVersion();
    byte[] tenantDataKey(UUID tenantId, String keyVersion);
}
