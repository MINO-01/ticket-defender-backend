package com.ticket.defender_core.application.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Executes bounded JDBC batches. No JPA persistence context holds parsed CSV records. */
@Component
public class BulkLedgerStagingWriter {
    private static final String INSERT = """
            INSERT INTO td_bulk_import_staging_rows
            (id, import_id, tenant_id, event_id, performance_id, source_row_no, reservation_reference_hmac,
             ticket_reference_hmac, account_reference_hmac, hmac_key_version, purchased_at, ticket_quantity,
             source_event_id, source_changed_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON DUPLICATE KEY UPDATE id=id
            """;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final int batchSize;

    public BulkLedgerStagingWriter(JdbcTemplate jdbc, TransactionTemplate transactions,
            @Value("${ticket-defender.bulk-ledger-upload.batch-size:1000}") int batchSize) {
        this.jdbc = jdbc; this.transactions = transactions;
        if (batchSize < 1 || batchSize > 2_000) throw new IllegalArgumentException("BL-02 JDBC batch size must be 1..2000");
        this.batchSize = batchSize;
    }

    public Session open(UUID importId, UUID tenantId) { return new Session(importId, tenantId); }
    public int batchSize() { return batchSize; }

    public final class Session {
        private final UUID importId;
        private final UUID tenantId;
        private final List<BulkLedgerCsvStagingParser.ParsedRow> pending = new ArrayList<>();
        private Session(UUID importId, UUID tenantId) { this.importId = importId; this.tenantId = tenantId; }
        public void accept(BulkLedgerCsvStagingParser.ParsedRow row) { pending.add(row); if (pending.size() >= batchSize) flush(); }
        public void finish() { flush(); }
        private void flush() {
            if (pending.isEmpty()) return;
            List<BulkLedgerCsvStagingParser.ParsedRow> batch = List.copyOf(pending); pending.clear();
        transactions.executeWithoutResult(ignored -> jdbc.batchUpdate(INSERT, new BatchPreparedStatementSetter() {
            @Override public void setValues(PreparedStatement statement, int index) throws SQLException {
                var row = batch.get(index);
                statement.setString(1, UUID.randomUUID().toString()); statement.setString(2, importId.toString());
                statement.setString(3, tenantId.toString()); statement.setString(4, row.eventId().toString());
                statement.setString(5, row.performanceId().toString()); statement.setLong(6, row.rowNumber());
                statement.setString(7, row.reservationToken()); statement.setString(8, row.ticketToken());
                statement.setString(9, row.accountToken()); statement.setString(10, row.keyVersion());
                statement.setTimestamp(11, Timestamp.valueOf(row.purchasedAt())); statement.setInt(12, row.ticketQuantity());
                statement.setString(13, row.sourceEventId());
                if (row.sourceChangedAt() == null) statement.setNull(14, java.sql.Types.TIMESTAMP); else statement.setTimestamp(14, Timestamp.valueOf(row.sourceChangedAt()));
            }
            @Override public int getBatchSize() { return batch.size(); }
        }));
        }
    }
}
