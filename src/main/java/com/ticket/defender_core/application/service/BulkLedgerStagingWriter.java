package com.ticket.defender_core.application.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

/** Executes bounded JDBC batches. No JPA persistence context holds parsed CSV records. */
@Component
public class BulkLedgerStagingWriter {
    private static final String INSERT = """
            INSERT INTO td_bulk_import_staging_rows
            (id, import_id, tenant_id, event_id, performance_id, source_row_no, reservation_reference_hmac,
             ticket_reference_hmac, account_reference_hmac, hmac_key_version, purchased_at, ticket_quantity,
             quantity_unit, ticket_status, source_version, source_event_id, source_changed_at, source_sequence, change_action, row_digest)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;
    /** v3 행만 쓰는 INSERT입니다. v1, v2 행은 기존 문장을 그대로 써서 새 컬럼에 의존하지 않습니다. */
    private static final String INSERT_V3 = """
            INSERT INTO td_bulk_import_staging_rows
            (id, import_id, tenant_id, event_id, performance_id, source_row_no, reservation_reference_hmac,
             ticket_reference_hmac, account_reference_hmac, hmac_key_version, purchased_at, ticket_quantity,
             quantity_unit, ticket_status, source_version, source_event_id, source_changed_at, source_sequence, change_action, row_digest,
             payment_hmac, address_hmac, device_hmac, ip_hmac, refund_account_hmac, phone_hmac, phone_source, ip_network_type, ip_network_type_source)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;
    private static final String INSERT_ENCRYPTED_REFERENCES = """
            INSERT INTO td_investigation_reference_ciphertexts
            (id, import_id, tenant_id, event_id, performance_id, source_row_no, ticket_reference_hmac,
             reservation_reference_hmac, account_reference_hmac, reference_ciphertext, nonce, encryption_key_version, created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,utc_timestamp(6))
            ON DUPLICATE KEY UPDATE id=id
            """;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final int batchSize;
    @Value("${ticket-defender.investigation-reveal.enabled:false}") private boolean referenceEncryptionEnabled;

    public BulkLedgerStagingWriter(JdbcTemplate jdbc, TransactionTemplate transactions,
            @Value("${ticket-defender.bulk-ledger-upload.batch-size:1000}") int batchSize) {
        this.jdbc = jdbc; this.transactions = transactions;
        if (batchSize < 1 || batchSize > 2_000) throw new IllegalArgumentException("BL-02 JDBC batch size must be 1..2000");
        this.batchSize = batchSize;
    }

    public Session open(UUID importId, UUID tenantId, long fence) { return new Session(importId, tenantId, fence); }
    public int batchSize() { return batchSize; }
    public long countForImport(UUID importId) {
        Long count = jdbc.queryForObject("select count(*) from td_bulk_import_staging_rows where import_id=?", Long.class, importId.toString());
        return count == null ? 0 : count;
    }
    public void deleteEncryptedForImport(UUID importId) {
        if (!referenceEncryptionEnabled) return;
        jdbc.update("delete from td_investigation_reference_ciphertexts where import_id=? and ledger_version_id is null", importId.toString());
    }
    public Map<String, Long> scopeCountsForImport(UUID importId) {
        Map<String, Long> counts = new HashMap<>();
        jdbc.query("select event_id, performance_id, count(*) from td_bulk_import_staging_rows where import_id=? group by event_id, performance_id",
                (RowCallbackHandler) rs -> counts.put(rs.getString(1) + ":" + rs.getString(2), rs.getLong(3)), importId.toString());
        return counts;
    }

    public final class Session {
        private final UUID importId;
        private final UUID tenantId;
        private final long fence;
        private final List<BulkLedgerCsvStagingParser.ParsedRow> pending = new ArrayList<>();
        private Session(UUID importId, UUID tenantId, long fence) { this.importId = importId; this.tenantId = tenantId; this.fence = fence; }
        public void accept(BulkLedgerCsvStagingParser.ParsedRow row) { pending.add(row); if (pending.size() >= batchSize) flush(); }
        public void finish() { flush(); }
        private void flush() {
            if (pending.isEmpty()) return;
            List<BulkLedgerCsvStagingParser.ParsedRow> batch = List.copyOf(pending); pending.clear();
        transactions.executeWithoutResult(ignored -> {
            // This locks and checks the job in the same transaction as the batch. It deliberately does not
            // infer validity from MySQL's affected-row reporting, which differs with CLIENT_FOUND_ROWS.
            List<Long> claimed = jdbc.query("select fence_token from td_bulk_import_jobs where id=? and tenant_id=? and status='STAGING' and fence_token=? and lease_until > utc_timestamp(6) for update",
                    (rs, rowIndex) -> rs.getLong(1), importId.toString(), tenantId.toString(), fence);
            if (claimed.size() != 1) throw new IllegalStateException("BULK_STAGING_FENCE_STALE");
            List<BulkLedgerCsvStagingParser.ParsedRow> inserts = new ArrayList<>();
            for (var row : batch) {
                List<String> existing = jdbc.query("select row_digest from td_bulk_import_staging_rows where import_id=? and source_row_no=?",
                        (rs, rowIndex) -> rs.getString(1), importId.toString(), row.rowNumber());
                if (existing.isEmpty()) inserts.add(row);
                else if (!existing.getFirst().equals(row.rowDigest())) throw new BulkLedgerStagingConflictException(row.rowNumber());
            }
            if (inserts.isEmpty()) return;
            boolean v3 = inserts.getFirst().signals() != null;
            if (inserts.stream().anyMatch(row -> (row.signals() != null) != v3)) throw new IllegalStateException("BULK_STAGING_SCHEMA_MIXED");
            jdbc.batchUpdate(v3 ? INSERT_V3 : INSERT, new BatchPreparedStatementSetter() {
            @Override public void setValues(PreparedStatement statement, int index) throws SQLException {
                var row = inserts.get(index);
                statement.setString(1, UUID.randomUUID().toString()); statement.setString(2, importId.toString());
                statement.setString(3, tenantId.toString()); statement.setString(4, row.eventId().toString());
                statement.setString(5, row.performanceId().toString()); statement.setLong(6, row.rowNumber());
                statement.setString(7, row.reservationToken()); statement.setString(8, row.ticketToken());
                statement.setString(9, row.accountToken()); statement.setString(10, row.keyVersion());
                statement.setTimestamp(11, Timestamp.valueOf(row.purchasedAt())); statement.setInt(12, row.ticketQuantity());
                statement.setString(13, row.quantityUnit()); statement.setString(14, row.ticketStatus()); statement.setString(15, row.sourceVersion());
                statement.setString(16, row.sourceEventId());
                if (row.sourceChangedAt() == null) statement.setNull(17, java.sql.Types.TIMESTAMP); else statement.setTimestamp(17, Timestamp.valueOf(row.sourceChangedAt()));
                if (row.sourceSequence() == null) statement.setNull(18, java.sql.Types.BIGINT); else statement.setLong(18, row.sourceSequence());
                statement.setString(19, row.changeAction());
                statement.setString(20, row.rowDigest());
                var signals = row.signals();
                if (signals != null) {
                    statement.setString(21, signals.paymentHmac()); statement.setString(22, signals.addressHmac());
                    statement.setString(23, signals.deviceHmac()); statement.setString(24, signals.ipHmac());
                    statement.setString(25, signals.refundAccountHmac()); statement.setString(26, signals.phoneHmac());
                    statement.setString(27, signals.phoneSource()); statement.setString(28, signals.ipNetworkType());
                    statement.setString(29, signals.ipNetworkTypeSource());
                }
            }
            @Override public int getBatchSize() { return inserts.size(); }
        });
            List<BulkLedgerCsvStagingParser.ParsedRow> encrypted = inserts.stream()
                    .filter(row -> row.encryptedReferences() != null).toList();
            if (!encrypted.isEmpty()) jdbc.batchUpdate(INSERT_ENCRYPTED_REFERENCES, new BatchPreparedStatementSetter() {
                @Override public void setValues(PreparedStatement statement, int index) throws SQLException {
                    var row = encrypted.get(index);
                    var value = row.encryptedReferences();
                    statement.setString(1, UUID.randomUUID().toString()); statement.setString(2, importId.toString());
                    statement.setString(3, tenantId.toString()); statement.setString(4, row.eventId().toString());
                    statement.setString(5, row.performanceId().toString()); statement.setLong(6, row.rowNumber());
                    statement.setString(7, row.ticketToken()); statement.setString(8, row.reservationToken());
                    statement.setString(9, row.accountToken()); statement.setBytes(10, value.ciphertext());
                    statement.setBytes(11, value.nonce()); statement.setString(12, value.keyVersion());
                }
                @Override public int getBatchSize() { return encrypted.size(); }
            });
        });
        }
    }
}
