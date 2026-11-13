package com.ticket.defender_core.application.service;

import com.ticket.defender_core.domain.BulkLedgerImportJob;
import com.ticket.defender_core.domain.BulkLedgerImportScope;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bounded, forward-only CSV parser. UTF-8 BOM is accepted once; all other malformed input is rejected.
 * It deliberately sends one row at a time to its consumer and never retains the source CSV or plain identifiers.
 */
public final class BulkLedgerCsvStagingParser {
    private static final int MAX_FIELD_LENGTH = 512;
    private static final int MAX_RECORD_CHARS = 8 * 1024;
    private static final Set<String> COMMON = Set.of("event_id", "performance_id", "reservation_reference",
            "ticket_reference", "account_reference", "purchased_at", "ticket_quantity");
    private static final Set<String> CHANGE = Set.of("source_event_id", "source_changed_at", "change_action");

    public record ParsedRow(long rowNumber, UUID eventId, UUID performanceId, String reservationToken,
                            String ticketToken, String accountToken, String keyVersion, LocalDateTime purchasedAt,
                            int ticketQuantity, String sourceEventId, LocalDateTime sourceChangedAt, String changeAction) { }
    public record Result(long rows) { }
    @FunctionalInterface public interface RowConsumer { void accept(ParsedRow row); }

    public Result parse(InputStream input, BulkLedgerImportJob.Mode mode, UUID tenantId,
                        List<BulkLedgerImportScope> scopes, TenantPurposeReferenceHasher reservationHasher,
                        TenantPurposeReferenceHasher ticketHasher, TenantPurposeReferenceHasher accountHasher,
                        RowConsumer consumer) {
        Map<String, BulkLedgerImportScope> scopeByKey = new HashMap<>();
        for (BulkLedgerImportScope scope : scopes) scopeByKey.put(key(scope.getEventId(), scope.getPerformanceId()), scope);
        Map<String, Long> scopeRows = new HashMap<>();
        try (InputStream bomAware = stripUtf8Bom(input);
             InputStreamReader reader = new InputStreamReader(bomAware, StandardCharsets.UTF_8.newDecoder()
                     .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT));
             CSVParser parser = new CSVParser(reader, CSVFormat.RFC4180)) {
            var iterator = parser.iterator();
            if (!iterator.hasNext()) throw failure(1, "BULK_CSV_HEADER_REQUIRED");
            CSVRecord header = iterator.next();
            Map<String, Integer> indexes = headers(header, mode);
            long rows = 0;
            while (iterator.hasNext()) {
                // Commons CSV record numbers include the header record, which matches the source line convention
                // for ordinary records while quoted newlines remain one logical CSV row.
                CSVRecord record = iterator.next(); long rowNumber = record.getRecordNumber();
                if (record.size() != header.size()) throw failure(rowNumber, "BULK_CSV_COLUMN_COUNT_INVALID");
                if (record.toString().length() > MAX_RECORD_CHARS) throw failure(rowNumber, "BULK_CSV_ROW_TOO_LONG");
                UUID event = uuid(value(record, indexes, "event_id", rowNumber), rowNumber, "BULK_CSV_EVENT_INVALID");
                UUID performance = uuid(value(record, indexes, "performance_id", rowNumber), rowNumber, "BULK_CSV_PERFORMANCE_INVALID");
                BulkLedgerImportScope scope = scopeByKey.get(key(event, performance));
                if (scope == null) throw failure(rowNumber, "BULK_CSV_SCOPE_NOT_DECLARED");
                String reservation = required(record, indexes, "reservation_reference", rowNumber);
                String ticket = required(record, indexes, "ticket_reference", rowNumber);
                String account = optional(record, indexes, "account_reference", rowNumber);
                LocalDateTime purchasedAt = timestamp(required(record, indexes, "purchased_at", rowNumber), rowNumber, "BULK_CSV_PURCHASED_AT_INVALID");
                int quantity = positive(required(record, indexes, "ticket_quantity", rowNumber), rowNumber);
                String sourceEventId = null; LocalDateTime sourceChangedAt = null; String changeAction = null;
                if (mode == BulkLedgerImportJob.Mode.CHANGE_FEED) {
                    sourceEventId = required(record, indexes, "source_event_id", rowNumber);
                    sourceChangedAt = timestamp(required(record, indexes, "source_changed_at", rowNumber), rowNumber, "BULK_CSV_CHANGED_AT_INVALID");
                    changeAction = required(record, indexes, "change_action", rowNumber);
                    if (!Set.of("UPSERT", "REMOVE", "CANCEL", "REFUND").contains(changeAction)) throw failure(rowNumber, "BULK_CSV_CHANGE_ACTION_INVALID");
                }
                long seen = scopeRows.merge(key(event, performance), 1L, Long::sum);
                if (seen > scope.getDeclaredRowCount()) throw failure(rowNumber, "BULK_CSV_SCOPE_ROW_COUNT_EXCEEDED");
                try { rows = Math.addExact(rows, 1); } catch (ArithmeticException overflow) { throw failure(rowNumber, "BULK_CSV_ROW_COUNT_OVERFLOW"); }
                consumer.accept(new ParsedRow(rowNumber, event, performance, reservationHasher.hash(tenantId, reservation),
                        ticketHasher.hash(tenantId, ticket), account == null ? null : accountHasher.hash(tenantId, account),
                        reservationHasher.keyVersion(), purchasedAt, quantity, sourceEventId, sourceChangedAt, changeAction));
            }
            for (BulkLedgerImportScope scope : scopes)
                if (scopeRows.getOrDefault(key(scope.getEventId(), scope.getPerformanceId()), 0L) != scope.getDeclaredRowCount())
                    throw failure(0, "BULK_CSV_SCOPE_ROW_COUNT_MISMATCH");
            return new Result(rows);
        } catch (BulkLedgerCsvValidationException rejected) { throw rejected; }
        catch (IOException | IllegalArgumentException malformed) { throw failure(0, "BULK_CSV_INVALID_UTF8_OR_SYNTAX"); }
    }

    private static InputStream stripUtf8Bom(InputStream input) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(input); buffered.mark(3);
        int a = buffered.read(), b = buffered.read(), c = buffered.read();
        if (!(a == 0xef && b == 0xbb && c == 0xbf)) buffered.reset();
        return buffered;
    }
    private static Map<String, Integer> headers(CSVRecord header, BulkLedgerImportJob.Mode mode) {
        Map<String, Integer> result = new HashMap<>(); Set<String> required = new HashSet<>(COMMON);
        if (mode == BulkLedgerImportJob.Mode.CHANGE_FEED) required.addAll(CHANGE);
        for (int index = 0; index < header.size(); index++) {
            String value = header.get(index);
            if (!required.contains(value) || result.putIfAbsent(value, index) != null) throw failure(1, "BULK_CSV_HEADER_INVALID");
        }
        if (!result.keySet().containsAll(required)) throw failure(1, "BULK_CSV_HEADER_REQUIRED");
        return result;
    }
    private static String value(CSVRecord r, Map<String, Integer> i, String name, long row) { return r.get(i.get(name)); }
    private static String required(CSVRecord r, Map<String, Integer> i, String name, long row) {
        String value = value(r, i, name, row).strip(); if (value.isEmpty() || value.length() > MAX_FIELD_LENGTH) throw failure(row, "BULK_CSV_FIELD_INVALID"); return value;
    }
    private static String optional(CSVRecord r, Map<String, Integer> i, String name, long row) {
        String value = value(r, i, name, row).strip(); if (value.isEmpty()) return null; if (value.length() > MAX_FIELD_LENGTH) throw failure(row, "BULK_CSV_FIELD_INVALID"); return value;
    }
    private static UUID uuid(String value, long row, String code) { try { return UUID.fromString(value); } catch (IllegalArgumentException invalid) { throw failure(row, code); } }
    private static LocalDateTime timestamp(String value, long row, String code) { try { return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(); } catch (RuntimeException invalid) { throw failure(row, code); } }
    private static int positive(String value, long row) { try { int result = Integer.parseInt(value); if (result > 0) return result; } catch (NumberFormatException ignored) { } throw failure(row, "BULK_CSV_QUANTITY_INVALID"); }
    private static String key(UUID event, UUID performance) { return event + ":" + performance; }
    private static BulkLedgerCsvValidationException failure(long row, String code) { return new BulkLedgerCsvValidationException(row, code); }
}
