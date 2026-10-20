-- MySQL 8.0.16 이상에서 관리자 공연 범위 테이블 생성 후 적용합니다.
-- 기존 예매에는 출처를 추정해 채우지 않습니다.
-- scope가 없는 기존 행은 Track 2 후보 조회에서 제외됩니다.
ALTER TABLE reservation
    ADD COLUMN tenant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN performance_ref VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL,
    MODIFY COLUMN zone VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    MODIFY COLUMN row_num VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    MODIFY COLUMN seat_num VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    ADD KEY idx_reservation_scope (tenant_id, event_id, performance_ref),
    ADD CONSTRAINT fk_reservation_scope
        FOREIGN KEY (tenant_id, event_id) REFERENCES events (tenant_id, id),
    ADD CONSTRAINT chk_reservation_scope
        CHECK ((tenant_id IS NULL AND event_id IS NULL AND performance_ref IS NULL)
            OR (tenant_id IS NOT NULL AND event_id IS NOT NULL AND performance_ref IS NOT NULL
                AND CHAR_LENGTH(TRIM(performance_ref)) > 0));

-- 기존 MySQL ENUM은 신규 Java enum 값만으로 확장되지 않습니다.
-- 기존 값의 순서를 유지해 이전 감사 상태를 보존합니다.
ALTER TABLE ticket_audit
    MODIFY COLUMN status ENUM('DUPLICATED', 'FRAUD_DETECTED', 'NORMAL', 'REPORT_ISSUED',
        'MATCH_CANDIDATE') NULL;
