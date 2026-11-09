-- Issue #79 additive MySQL 8.0 migration. Run after track2-candidate-pipeline-migration.sql only.
-- It does not alter the legacy reservation.reservation_no UNIQUE constraint or historical reservation rows.
ALTER TABLE reservation_candidates
    ADD COLUMN ticket_ref_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER reservation_ref_hmac;

ALTER TABLE reservation_candidates
    DROP INDEX uk_reservation_candidate_reference,
    ADD UNIQUE KEY uk_reservation_candidate_reference_seat
        (batch_id, reservation_ref_hmac, zone, row_num, seat_num),
    ADD UNIQUE KEY uk_reservation_candidate_reference_ticket
        (batch_id, reservation_ref_hmac, ticket_ref_hmac);
