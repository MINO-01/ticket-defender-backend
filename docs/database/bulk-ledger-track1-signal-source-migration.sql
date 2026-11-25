-- BL-V3: 원본 신호 수신 경로가 공개 시점에 기록하는 신호 출처 표시입니다.
-- 적용 순서: bulk-ledger-score-v2-exp2-signal-migration.sql 다음.
-- 기존 HMAC 신호 등록 API로 저장된 행은 두 컬럼이 모두 NULL입니다.
ALTER TABLE td_bulk_ledger_track1_ticket_signals
    ADD COLUMN phone_source VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER ip_network_type,
    ADD COLUMN ip_network_type_source VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER phone_source,
    ADD CONSTRAINT chk_td_bl06_signal_sources CHECK (
        (phone_source IS NULL OR (phone_hmac IS NOT NULL AND phone_source IN ('RECIPIENT','MEMBER')))
        AND (ip_network_type_source IS NULL OR (ip_network_type IS NOT NULL
             AND ip_network_type_source IN ('DECLARED','CLASSIFIED','UNKNOWN')))
    );
