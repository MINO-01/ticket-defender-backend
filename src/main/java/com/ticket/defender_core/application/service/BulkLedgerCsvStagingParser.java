package com.ticket.defender_core.application.service;

import com.ticket.defender_core.domain.BulkLedgerImportJob;
import com.ticket.defender_core.domain.BulkLedgerImportScope;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

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
 * It sends one row at a time to its consumer and never retains the source CSV or plain identifiers. If PR 7 is enabled,
 * the three approved references are immediately encrypted and only their ciphertext leaves this parser.
 */
@Component
public final class BulkLedgerCsvStagingParser {
    private static final int MAX_FIELD_LENGTH = 512;
    private static final int MAX_RECORD_CHARS = 8 * 1024;
    private static final Set<String> COMMON = Set.of("event_id", "performance_id", "reservation_reference",
            "ticket_reference", "account_reference", "purchased_at", "ticket_quantity");
    private static final Set<String> V2 = Set.of("quantity_unit", "ticket_status", "source_version");
    private static final Set<String> CHANGE = Set.of("source_event_id", "source_changed_at", "change_action");
    private static final Set<String> SUPPLIER_SCOPE = Set.of("source_event_code", "source_performance_code", "event_title", "performance_starts_at");
    private static final Set<String> ROW_BASE = Set.of("reservation_reference", "ticket_reference", "account_reference", "purchased_at", "ticket_quantity");
    private static final Set<String> V3_FIELDS = Set.of("seat_zone", "seat_row", "seat_number", "reserved_at", "paid_at", "cancelled_at", "account_created_at");
    /** v3 선택 원본 신호 열입니다. 값은 읽는 즉시 정규화하고 HMAC하며 원본은 이 메서드 밖으로 나가지 않습니다. */
    static final Set<String> V3_OPTIONAL = Set.of("device_reference", "ip_reference", "ip_network_type", "delivery_address",
            "recipient_phone", "member_phone", "payment_masked_card", "payment_issuer_code", "payment_method_type",
            "refund_bank_code", "refund_account_number");

    public record ParsedRow(long rowNumber, UUID eventId, UUID performanceId, String reservationToken,
                            String ticketToken, String accountToken, String keyVersion, LocalDateTime purchasedAt,
                            int ticketQuantity, String quantityUnit, String ticketStatus, String sourceVersion,
                            String sourceEventId, LocalDateTime sourceChangedAt, Long sourceSequence,
                            String changeAction, String rowDigest, RawSignals signals, String seatZone, String seatRow,
                            String seatNumber, LocalDateTime reservedAt, LocalDateTime paidAt, LocalDateTime cancelledAt,
                            LocalDateTime accountCreatedAt, InvestigationReferenceCipher.Encrypted encryptedReferences) {
        public ParsedRow(long rowNumber, UUID eventId, UUID performanceId, String reservationToken,
                         String ticketToken, String accountToken, String keyVersion, LocalDateTime purchasedAt,
                         int ticketQuantity, String quantityUnit, String ticketStatus, String sourceVersion,
                         String sourceEventId, LocalDateTime sourceChangedAt, Long sourceSequence,
                         String changeAction, String rowDigest, RawSignals signals) {
            this(rowNumber, eventId, performanceId, reservationToken, ticketToken, accountToken, keyVersion, purchasedAt,
                    ticketQuantity, quantityUnit, ticketStatus, sourceVersion, sourceEventId, sourceChangedAt, sourceSequence,
                    changeAction, rowDigest, signals, null, null, null, null, null, null, null, null);
        }
        public ParsedRow(long rowNumber, UUID eventId, UUID performanceId, String reservationToken,
                         String ticketToken, String accountToken, String keyVersion, LocalDateTime purchasedAt,
                         int ticketQuantity, String quantityUnit, String ticketStatus, String sourceVersion,
                         String sourceEventId, LocalDateTime sourceChangedAt, Long sourceSequence,
                         String changeAction, String rowDigest, RawSignals signals,
                         InvestigationReferenceCipher.Encrypted encryptedReferences) {
            this(rowNumber, eventId, performanceId, reservationToken, ticketToken, accountToken, keyVersion, purchasedAt,
                    ticketQuantity, quantityUnit, ticketStatus, sourceVersion, sourceEventId, sourceChangedAt, sourceSequence,
                    changeAction, rowDigest, signals, null, null, null, null, null, null, null, encryptedReferences);
        }
        public ParsedRow(long rowNumber, UUID eventId, UUID performanceId, String reservationToken,
                         String ticketToken, String accountToken, String keyVersion, LocalDateTime purchasedAt,
                         int ticketQuantity, String quantityUnit, String ticketStatus, String sourceVersion,
                         String sourceEventId, LocalDateTime sourceChangedAt, Long sourceSequence,
                         String changeAction, String rowDigest, RawSignals signals, String seatZone, String seatRow,
                         String seatNumber, LocalDateTime reservedAt, LocalDateTime paidAt, LocalDateTime cancelledAt,
                         LocalDateTime accountCreatedAt) {
            this(rowNumber, eventId, performanceId, reservationToken, ticketToken, accountToken, keyVersion, purchasedAt,
                    ticketQuantity, quantityUnit, ticketStatus, sourceVersion, sourceEventId, sourceChangedAt, sourceSequence,
                    changeAction, rowDigest, signals, seatZone, seatRow, seatNumber, reservedAt, paidAt, cancelledAt,
                    accountCreatedAt, null);
        }
        public ParsedRow(long rowNumber, UUID eventId, UUID performanceId, String reservationToken,
                         String ticketToken, String accountToken, String keyVersion, LocalDateTime purchasedAt,
                         int ticketQuantity, String quantityUnit, String ticketStatus, String sourceVersion,
                         String sourceEventId, LocalDateTime sourceChangedAt, Long sourceSequence,
                         String changeAction, String rowDigest) {
            this(rowNumber, eventId, performanceId, reservationToken, ticketToken, accountToken, keyVersion, purchasedAt,
                    ticketQuantity, quantityUnit, ticketStatus, sourceVersion, sourceEventId, sourceChangedAt, sourceSequence,
                    changeAction, rowDigest, null, null, null, null, null, null, null, null, null);
        }
        public ParsedRow(long rowNumber, UUID eventId, UUID performanceId, String reservationToken,
                         String ticketToken, String accountToken, String keyVersion, LocalDateTime purchasedAt,
                         int ticketQuantity, String sourceEventId, LocalDateTime sourceChangedAt, String changeAction, String rowDigest) {
            this(rowNumber, eventId, performanceId, reservationToken, ticketToken, accountToken, keyVersion, purchasedAt,
                    ticketQuantity, null, null, null, sourceEventId, sourceChangedAt, null, changeAction,
                    rowDigest, null, null, null, null, null, null, null, null, null);
        }
    }
    /** v3 행의 HMAC 토큰과 출처 표시입니다. 원본 값은 담지 않습니다. */
    public record RawSignals(String paymentHmac, String addressHmac, String deviceHmac, String ipHmac,
                             String refundAccountHmac, String phoneHmac, String phoneSource,
                             String ipNetworkType, String ipNetworkTypeSource) { }
    public record Result(long rows) { }
    public record SupplierScope(String sourceEventCode, String sourcePerformanceCode, String eventTitle,
            LocalDateTime performanceStartsAt, Integer perAccountLimit, long declaredRowCount,
            UUID eventId, UUID performanceId) { }
    @FunctionalInterface public interface RowConsumer { void accept(ParsedRow row); }

    private final InvestigationReferenceCipher referenceCipher;

    public BulkLedgerCsvStagingParser() { this.referenceCipher = null; }

    @Autowired
    public BulkLedgerCsvStagingParser(InvestigationReferenceCipher referenceCipher) {
        this.referenceCipher = referenceCipher;
    }

    public Result parse(InputStream input, BulkLedgerImportJob.Mode mode, UUID tenantId,
                        List<BulkLedgerImportScope> scopes, TenantPurposeReferenceHasher reservationHasher,
                        TenantPurposeReferenceHasher ticketHasher, TenantPurposeReferenceHasher accountHasher,
                        RowConsumer consumer) {
        return parse(input, "v1", mode, tenantId, scopes, reservationHasher, ticketHasher, accountHasher, consumer);
    }

    public Result parse(InputStream input, String schemaVersion, BulkLedgerImportJob.Mode mode, UUID tenantId,
                        List<BulkLedgerImportScope> scopes, TenantPurposeReferenceHasher reservationHasher,
                        TenantPurposeReferenceHasher ticketHasher, TenantPurposeReferenceHasher accountHasher,
                        RowConsumer consumer) {
        return parse(input, schemaVersion, mode, tenantId, scopes, reservationHasher, ticketHasher, accountHasher, null, consumer);
    }

    public Result parse(InputStream input, String schemaVersion, BulkLedgerImportJob.Mode mode, UUID tenantId,
                        List<BulkLedgerImportScope> scopes, TenantPurposeReferenceHasher reservationHasher,
                        TenantPurposeReferenceHasher ticketHasher, TenantPurposeReferenceHasher accountHasher,
                        BulkLedgerRawSignalHashers signalHashers, RowConsumer consumer) {
        return parse(null, input, schemaVersion, mode, tenantId, scopes, reservationHasher, ticketHasher, accountHasher, signalHashers, consumer);
    }

    public Result parse(UUID importId, InputStream input, String schemaVersion, BulkLedgerImportJob.Mode mode, UUID tenantId,
                        List<BulkLedgerImportScope> scopes, TenantPurposeReferenceHasher reservationHasher,
                        TenantPurposeReferenceHasher ticketHasher, TenantPurposeReferenceHasher accountHasher,
                        BulkLedgerRawSignalHashers signalHashers, RowConsumer consumer) {
        return parseInternal(importId, input, schemaVersion, mode, tenantId, scopes, List.of(), false,
                reservationHasher, ticketHasher, accountHasher, signalHashers, consumer);
    }

    public Result parseSupplier(InputStream input, BulkLedgerImportJob.Mode mode, UUID tenantId,
            List<SupplierScope> supplierScopes, TenantPurposeReferenceHasher reservationHasher,
            TenantPurposeReferenceHasher ticketHasher, TenantPurposeReferenceHasher accountHasher,
            BulkLedgerRawSignalHashers signalHashers, RowConsumer consumer) {
        return parseSupplier(null, input, mode, tenantId, supplierScopes, reservationHasher, ticketHasher, accountHasher,
                signalHashers, consumer);
    }

    public Result parseSupplier(UUID importId, InputStream input, BulkLedgerImportJob.Mode mode, UUID tenantId,
            List<SupplierScope> supplierScopes, TenantPurposeReferenceHasher reservationHasher,
            TenantPurposeReferenceHasher ticketHasher, TenantPurposeReferenceHasher accountHasher,
            BulkLedgerRawSignalHashers signalHashers, RowConsumer consumer) {
        return parseInternal(importId, input, "v3", mode, tenantId, List.of(), supplierScopes, true,
                reservationHasher, ticketHasher, accountHasher, signalHashers, consumer);
    }

    private Result parseInternal(UUID importId, InputStream input, String schemaVersion, BulkLedgerImportJob.Mode mode, UUID tenantId,
                        List<BulkLedgerImportScope> scopes, List<SupplierScope> supplierScopes, boolean supplierMode,
                        TenantPurposeReferenceHasher reservationHasher, TenantPurposeReferenceHasher ticketHasher,
                        TenantPurposeReferenceHasher accountHasher, BulkLedgerRawSignalHashers signalHashers,
                        RowConsumer consumer) {
        boolean v3 = "v3".equals(schemaVersion);
        // v3는 v2 필수 열과 원장 사실 검증을 그대로 포함합니다.
        boolean v2 = "v2".equals(schemaVersion) || v3;
        if (v3 && (signalHashers == null || !signalHashers.keyVersion().equals(reservationHasher.keyVersion())))
            throw new IllegalStateException("v3 raw signal hashers must share the staging HMAC key version");
        Map<String, BulkLedgerImportScope> scopeByKey = new HashMap<>();
        for (BulkLedgerImportScope scope : scopes) scopeByKey.put(key(scope.getEventId(), scope.getPerformanceId()), scope);
        Map<String, SupplierScope> supplierByCode = new HashMap<>();
        if (supplierMode) {
            if (supplierScopes == null || supplierScopes.isEmpty()) throw failure(1, "BULK_CSV_SUPPLIER_SCOPE_REQUIRED");
            for (SupplierScope scope : supplierScopes) {
                if (scope.eventId() == null || scope.performanceId() == null
                        || supplierByCode.putIfAbsent(codeKey(scope.sourceEventCode(), scope.sourcePerformanceCode()), scope) != null)
                    throw failure(1, "BULK_CSV_SUPPLIER_SCOPE_INVALID");
                scopeByKey.put(key(scope.eventId(), scope.performanceId()), null);
            }
        }
        Map<String, Long> scopeRows = new HashMap<>();
        try (InputStream bomAware = stripUtf8Bom(input);
             InputStreamReader reader = new InputStreamReader(bomAware, StandardCharsets.UTF_8.newDecoder()
                     .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT));
             CSVParser parser = new CSVParser(reader, CSVFormat.RFC4180)) {
            var iterator = parser.iterator();
            if (!iterator.hasNext()) throw failure(1, "BULK_CSV_HEADER_REQUIRED");
            CSVRecord header = iterator.next();
            Map<String, Integer> indexes = headers(header, mode, v2, v3, supplierMode);
            long rows = 0;
            while (iterator.hasNext()) {
                // Commons CSV record numbers include the header record, which matches the source line convention
                // for ordinary records while quoted newlines remain one logical CSV row.
                CSVRecord record = iterator.next(); long rowNumber = record.getRecordNumber();
                if (record.size() != header.size()) throw failure(rowNumber, "BULK_CSV_COLUMN_COUNT_INVALID");
                if (record.toString().length() > MAX_RECORD_CHARS) throw failure(rowNumber, "BULK_CSV_ROW_TOO_LONG");
                UUID event;
                UUID performance;
                long declaredScopeRows;
                if (supplierMode) {
                    String eventCode = boundedRequired(record, indexes, "source_event_code", rowNumber, 128);
                    String performanceCode = boundedRequired(record, indexes, "source_performance_code", rowNumber, 128);
                    SupplierScope scope = supplierByCode.get(codeKey(eventCode, performanceCode));
                    if (scope == null) throw failure(rowNumber, "BULK_CSV_SCOPE_NOT_DECLARED");
                    String title = boundedRequired(record, indexes, "event_title", rowNumber, 255);
                    LocalDateTime startsAt = timestamp(required(record, indexes, "performance_starts_at", rowNumber), rowNumber, "BULK_CSV_PERFORMANCE_STARTS_AT_INVALID");
                    Integer perAccountLimit = optionalPositive(record, indexes, "per_account_limit", rowNumber);
                    if (!scope.eventTitle().equals(title) || !scope.performanceStartsAt().equals(startsAt)
                            || !java.util.Objects.equals(scope.perAccountLimit(), perAccountLimit))
                        throw failure(rowNumber, "BULK_CSV_PERFORMANCE_METADATA_MISMATCH");
                    event = scope.eventId(); performance = scope.performanceId(); declaredScopeRows = scope.declaredRowCount();
                } else {
                    event = uuid(value(record, indexes, "event_id", rowNumber), rowNumber, "BULK_CSV_EVENT_INVALID");
                    performance = uuid(value(record, indexes, "performance_id", rowNumber), rowNumber, "BULK_CSV_PERFORMANCE_INVALID");
                    BulkLedgerImportScope scope = scopeByKey.get(key(event, performance));
                    if (scope == null) throw failure(rowNumber, "BULK_CSV_SCOPE_NOT_DECLARED");
                    declaredScopeRows = scope.getDeclaredRowCount();
                }
                String reservation = required(record, indexes, "reservation_reference", rowNumber);
                String ticket = required(record, indexes, "ticket_reference", rowNumber);
                String account = optional(record, indexes, "account_reference", rowNumber);
                LocalDateTime purchasedAt = timestamp(required(record, indexes, "purchased_at", rowNumber), rowNumber, "BULK_CSV_PURCHASED_AT_INVALID");
                int quantity = positive(required(record, indexes, "ticket_quantity", rowNumber), rowNumber);
                String quantityUnit = null, ticketStatus = null, sourceVersion = null;
                if (v2) {
                    quantityUnit = required(record, indexes, "quantity_unit", rowNumber);
                    ticketStatus = required(record, indexes, "ticket_status", rowNumber);
                    sourceVersion = required(record, indexes, "source_version", rowNumber);
                    if (!Set.of("TICKET", "ORDER_LINE").contains(quantityUnit)
                            || !Set.of("ACTIVE", "CANCELLED", "REFUNDED").contains(ticketStatus)
                            || ("TICKET".equals(quantityUnit) && quantity != 1))
                        throw failure(rowNumber, "BULK_CSV_LEDGER_FACT_INVALID");
                }
                String sourceEventId = null; LocalDateTime sourceChangedAt = null; Long sourceSequence = null; String changeAction = null;
                if (mode == BulkLedgerImportJob.Mode.CHANGE_FEED) {
                    sourceEventId = required(record, indexes, "source_event_id", rowNumber);
                    sourceChangedAt = timestamp(required(record, indexes, "source_changed_at", rowNumber), rowNumber, "BULK_CSV_CHANGED_AT_INVALID");
                    if (v2) sourceSequence = nonNegative(required(record, indexes, "source_sequence", rowNumber), rowNumber, "BULK_CSV_SOURCE_SEQUENCE_INVALID");
                    changeAction = required(record, indexes, "change_action", rowNumber);
                    if (!Set.of("UPSERT", "REMOVE", "CANCEL", "REFUND").contains(changeAction)) throw failure(rowNumber, "BULK_CSV_CHANGE_ACTION_INVALID");
                }
                long seen = scopeRows.merge(key(event, performance), 1L, Long::sum);
                if (seen > declaredScopeRows) throw failure(rowNumber, "BULK_CSV_SCOPE_ROW_COUNT_EXCEEDED");
                try { rows = Math.addExact(rows, 1); } catch (ArithmeticException overflow) { throw failure(rowNumber, "BULK_CSV_ROW_COUNT_OVERFLOW"); }
                RawSignals signals = v3 ? signals(record, indexes, rowNumber, tenantId, signalHashers) : null;
                String seatZone = v3 ? optionalBounded(record, indexes, "seat_zone", rowNumber, 64) : null;
                String seatRow = v3 ? optionalBounded(record, indexes, "seat_row", rowNumber, 64) : null;
                String seatNumber = v3 ? optionalBounded(record, indexes, "seat_number", rowNumber, 64) : null;
                LocalDateTime reservedAt = v3 ? optionalTimestamp(record, indexes, "reserved_at", rowNumber) : null;
                LocalDateTime paidAt = v3 ? optionalTimestamp(record, indexes, "paid_at", rowNumber) : null;
                LocalDateTime cancelledAt = v3 ? optionalTimestamp(record, indexes, "cancelled_at", rowNumber) : null;
                LocalDateTime accountCreatedAt = v3 ? optionalTimestamp(record, indexes, "account_created_at", rowNumber) : null;
                String reservationToken = reservationHasher.hash(tenantId, reservation), ticketToken = ticketHasher.hash(tenantId, ticket);
                String accountToken = account == null ? null : accountHasher.hash(tenantId, account);
                InvestigationReferenceCipher.Encrypted encryptedReferences = referenceCipher == null || importId == null ? null
                        : referenceCipher.encrypt(importId, tenantId, event, performance, rowNumber, ticketToken,
                                reservationToken, accountToken, account, reservation, ticket);
                String digest = v3
                        ? CandidatePipelineSupport.sha256("BULK_CSV_V3", event.toString(), performance.toString(), reservationToken, ticketToken,
                                String.valueOf(accountToken), reservationHasher.keyVersion(), purchasedAt.toString(), Integer.toString(quantity),
                                String.valueOf(quantityUnit), String.valueOf(ticketStatus), String.valueOf(sourceVersion),
                                String.valueOf(sourceEventId), String.valueOf(sourceChangedAt), String.valueOf(sourceSequence), String.valueOf(changeAction),
                                String.valueOf(signals.paymentHmac()), String.valueOf(signals.addressHmac()), String.valueOf(signals.deviceHmac()),
                                String.valueOf(signals.ipHmac()), String.valueOf(signals.refundAccountHmac()), String.valueOf(signals.phoneHmac()),
                                String.valueOf(signals.phoneSource()), String.valueOf(signals.ipNetworkType()), String.valueOf(signals.ipNetworkTypeSource()),
                                String.valueOf(seatZone), String.valueOf(seatRow), String.valueOf(seatNumber), String.valueOf(reservedAt),
                                String.valueOf(paidAt), String.valueOf(cancelledAt), String.valueOf(accountCreatedAt))
                        : v2
                        ? CandidatePipelineSupport.sha256(event.toString(), performance.toString(), reservationToken, ticketToken,
                                String.valueOf(accountToken), reservationHasher.keyVersion(), purchasedAt.toString(), Integer.toString(quantity),
                                String.valueOf(quantityUnit), String.valueOf(ticketStatus), String.valueOf(sourceVersion),
                                String.valueOf(sourceEventId), String.valueOf(sourceChangedAt), String.valueOf(sourceSequence), String.valueOf(changeAction))
                        : CandidatePipelineSupport.sha256(event.toString(), performance.toString(), reservationToken, ticketToken,
                                String.valueOf(accountToken), reservationHasher.keyVersion(), purchasedAt.toString(), Integer.toString(quantity),
                                String.valueOf(sourceEventId), String.valueOf(sourceChangedAt), String.valueOf(changeAction));
                consumer.accept(new ParsedRow(rowNumber, event, performance, reservationToken, ticketToken, accountToken,
                        reservationHasher.keyVersion(), purchasedAt, quantity, quantityUnit, ticketStatus, sourceVersion,
                        sourceEventId, sourceChangedAt, sourceSequence, changeAction, digest, signals, seatZone, seatRow,
                        seatNumber, reservedAt, paidAt, cancelledAt, accountCreatedAt, encryptedReferences));
            }
            for (String scopeKey : scopeByKey.keySet()) {
                long declared = supplierMode
                        ? supplierScopes.stream().filter(scope -> key(scope.eventId(), scope.performanceId()).equals(scopeKey))
                            .mapToLong(SupplierScope::declaredRowCount).findFirst().orElse(-1)
                        : scopes.stream().filter(scope -> key(scope.getEventId(), scope.getPerformanceId()).equals(scopeKey))
                            .mapToLong(BulkLedgerImportScope::getDeclaredRowCount).findFirst().orElse(-1);
                if (scopeRows.getOrDefault(scopeKey, 0L) != declared)
                    throw failure(0, "BULK_CSV_SCOPE_ROW_COUNT_MISMATCH");
            }
            return new Result(rows);
        } catch (BulkLedgerCsvValidationException rejected) { throw rejected; }
        // commons-csv는 따옴표 오류 같은 문법 오류를 UncheckedIOException으로 던집니다. 재시도할 수 있는 장애가 아니라
        // 입력 거절이며, 원인 메시지는 버리고 고정 코드만 남깁니다.
        catch (IOException | java.io.UncheckedIOException | IllegalArgumentException malformed) { throw failure(0, "BULK_CSV_INVALID_UTF8_OR_SYNTAX"); }
    }

    private static InputStream stripUtf8Bom(InputStream input) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(input); buffered.mark(3);
        int a = buffered.read(), b = buffered.read(), c = buffered.read();
        if (!(a == 0xef && b == 0xbb && c == 0xbf)) buffered.reset();
        return buffered;
    }
    /**
     * 각 원본 신호 열을 정규화한 즉시 신호별 목적의 HMAC으로 바꿉니다. 정규화된 원본 문자열은 지역 변수로만 존재하고
     * 반환 객체, 컬렉션, 예외 메시지에 들어가지 않습니다.
     */
    private static RawSignals signals(CSVRecord record, Map<String, Integer> indexes, long row, UUID tenantId,
                                      BulkLedgerRawSignalHashers hashers) {
        String device = BulkLedgerRawSignalNormalizer.device(optionalColumn(record, indexes, "device_reference", row));
        String deviceHmac = device == null ? null : hashers.device().hash(tenantId, device);

        String ip = BulkLedgerRawSignalNormalizer.ipv4(optionalColumn(record, indexes, "ip_reference", row), row);
        String declaredType = BulkLedgerRawSignalNormalizer.networkType(optionalColumn(record, indexes, "ip_network_type", row), row);
        if (declaredType != null && ip == null) throw failure(row, "BULK_CSV_IP_NETWORK_TYPE_INVALID");
        String ipHmac = null, networkType = null, networkSource = null;
        if (ip != null) {
            ipHmac = hashers.ip().hash(tenantId, ip);
            if (declaredType != null) { networkType = declaredType; networkSource = "DECLARED"; }
            else {
                var classified = hashers.classifier().classify(ip);
                networkType = classified.orElse("UNKNOWN");
                networkSource = classified.isPresent() ? "CLASSIFIED" : "UNKNOWN";
            }
        }

        String address = BulkLedgerRawSignalNormalizer.address(optionalColumn(record, indexes, "delivery_address", row), row);
        String addressHmac = address == null ? null : hashers.address().hash(tenantId, address);

        String recipient = BulkLedgerRawSignalNormalizer.phone(optionalColumn(record, indexes, "recipient_phone", row), row);
        String member = BulkLedgerRawSignalNormalizer.phone(optionalColumn(record, indexes, "member_phone", row), row);
        String phoneHmac = recipient != null ? hashers.phone().hash(tenantId, recipient)
                : member != null ? hashers.phone().hash(tenantId, member) : null;
        String phoneSource = recipient != null ? "RECIPIENT" : member != null ? "MEMBER" : null;

        String card = BulkLedgerRawSignalNormalizer.maskedCard(optionalColumn(record, indexes, "payment_masked_card", row), row);
        String issuer = BulkLedgerRawSignalNormalizer.upper(optionalColumn(record, indexes, "payment_issuer_code", row));
        String method = BulkLedgerRawSignalNormalizer.upper(optionalColumn(record, indexes, "payment_method_type", row));
        if ((card == null) != (issuer == null) || (card == null) != (method == null)) throw failure(row, "BULK_CSV_PAYMENT_INCOMPLETE");
        String paymentHmac = card == null ? null : hashers.payment().hash(tenantId, card + "|" + issuer + "|" + method);

        String bank = BulkLedgerRawSignalNormalizer.digits(optionalColumn(record, indexes, "refund_bank_code", row), row, "BULK_CSV_REFUND_ACCOUNT_INCOMPLETE");
        String accountNumber = BulkLedgerRawSignalNormalizer.digits(optionalColumn(record, indexes, "refund_account_number", row), row, "BULK_CSV_REFUND_ACCOUNT_INCOMPLETE");
        if ((bank == null) != (accountNumber == null)) throw failure(row, "BULK_CSV_REFUND_ACCOUNT_INCOMPLETE");
        String refundHmac = bank == null ? null : hashers.refundAccount().hash(tenantId, bank + "|" + accountNumber);

        return new RawSignals(paymentHmac, addressHmac, deviceHmac, ipHmac, refundHmac, phoneHmac, phoneSource, networkType, networkSource);
    }

    private static String optionalColumn(CSVRecord record, Map<String, Integer> indexes, String name, long row) {
        Integer index = indexes.get(name);
        if (index == null) return null;
        String value = record.get(index);
        if (value.length() > MAX_FIELD_LENGTH) throw failure(row, "BULK_CSV_FIELD_INVALID");
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static Map<String, Integer> headers(CSVRecord header, BulkLedgerImportJob.Mode mode, boolean v2, boolean v3, boolean supplierMode) {
        Map<String, Integer> result = new HashMap<>(); Set<String> required = new HashSet<>(supplierMode ? ROW_BASE : COMMON);
        if (supplierMode) required.addAll(SUPPLIER_SCOPE);
        if (v2) required.addAll(V2);
        if (mode == BulkLedgerImportJob.Mode.CHANGE_FEED) {
            required.addAll(CHANGE);
            if (v2) required.add("source_sequence");
        }
        Set<String> allowed = new HashSet<>(required);
        if (v3) { allowed.addAll(V3_OPTIONAL); allowed.addAll(V3_FIELDS); if (supplierMode) allowed.add("per_account_limit"); }
        for (int index = 0; index < header.size(); index++) {
            String value = header.get(index);
            if (!allowed.contains(value) || result.putIfAbsent(value, index) != null) throw failure(1, "BULK_CSV_HEADER_INVALID");
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
    private static String boundedRequired(CSVRecord r, Map<String, Integer> i, String name, long row, int max) {
        String value = required(r, i, name, row);
        if (value.length() > max) throw failure(row, "BULK_CSV_FIELD_INVALID");
        return value;
    }
    private static String optionalBounded(CSVRecord r, Map<String, Integer> i, String name, long row, int max) {
        Integer index = i.get(name);
        if (index == null) return null;
        String value = r.get(index).strip();
        if (value.isEmpty()) return null;
        if (value.length() > max) throw failure(row, "BULK_CSV_FIELD_INVALID");
        return value;
    }
    private static LocalDateTime optionalTimestamp(CSVRecord r, Map<String, Integer> i, String name, long row) {
        Integer index = i.get(name);
        if (index == null || r.get(index).strip().isEmpty()) return null;
        return timestamp(r.get(index).strip(), row, "BULK_CSV_" + name.toUpperCase(java.util.Locale.ROOT) + "_INVALID");
    }
    private static Integer optionalPositive(CSVRecord r, Map<String, Integer> i, String name, long row) {
        Integer index = i.get(name);
        if (index == null || r.get(index).strip().isEmpty()) return null;
        try {
            int result = Integer.parseInt(r.get(index).strip());
            if (result > 0) return result;
        } catch (NumberFormatException ignored) { }
        throw failure(row, "BULK_CSV_PER_ACCOUNT_LIMIT_INVALID");
    }
    private static UUID uuid(String value, long row, String code) { try { return UUID.fromString(value); } catch (IllegalArgumentException invalid) { throw failure(row, code); } }
    private static LocalDateTime timestamp(String value, long row, String code) { try { return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(); } catch (RuntimeException invalid) { throw failure(row, code); } }
    private static int positive(String value, long row) { try { int result = Integer.parseInt(value); if (result > 0) return result; } catch (NumberFormatException ignored) { } throw failure(row, "BULK_CSV_QUANTITY_INVALID"); }
    private static long nonNegative(String value, long row, String code) { try { long result = Long.parseLong(value); if (result >= 0) return result; } catch (NumberFormatException ignored) { } throw failure(row, code); }
    private static String key(UUID event, UUID performance) { return event + ":" + performance; }
    private static String codeKey(String eventCode, String performanceCode) { return eventCode.length() + "#" + eventCode + performanceCode; }
    private static BulkLedgerCsvValidationException failure(long row, String code) { return new BulkLedgerCsvValidationException(row, code); }
}
