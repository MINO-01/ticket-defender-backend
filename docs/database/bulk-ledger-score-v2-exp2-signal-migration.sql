-- score-v2-exp2 연결 신호의 additive 확장입니다.
-- 기존 exp1 분석과 공개 원장 버전은 변경하지 않습니다.
ALTER TABLE td_bulk_ledger_track1_ticket_signals
    DROP CHECK chk_td_bl06_signal_values;

ALTER TABLE td_bulk_ledger_track1_ticket_signals
    ADD COLUMN refund_account_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER ip_hmac,
    ADD COLUMN phone_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER refund_account_hmac,
    ADD COLUMN ip_network_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER phone_hmac,
    ADD CONSTRAINT chk_td_bl06_signal_values CHECK (
        hmac_purpose = 'TRACK1_CONNECTION'
        AND (payment_hmac IS NOT NULL OR address_hmac IS NOT NULL OR device_hmac IS NOT NULL OR ip_hmac IS NOT NULL
             OR refund_account_hmac IS NOT NULL OR phone_hmac IS NOT NULL)
        AND (ip_network_type IS NULL OR (ip_hmac IS NOT NULL AND ip_network_type IN
             ('MOBILE_CGNAT','RESIDENTIAL','CORPORATE_EDU','DATACENTER_PROXY','UNKNOWN')))
    );
