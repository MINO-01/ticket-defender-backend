-- MySQL 개발 DB에서 한 번만 실행
-- Hibernate ddl-auto:update로 새 컬럼과 조회 인덱스가 생성된 후 실행
-- 공연·예매 범위를 구분하지 못하는 기존 제약은 제거
ALTER TABLE ticket_audit DROP INDEX uk_payment_account;
