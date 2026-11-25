package com.ticket.defender_core.application.service;

import com.ticket.defender_core.domain.BulkLedgerImportJob;
import com.ticket.defender_core.domain.BulkLedgerImportScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 대량 CSV v3 원본 신호 수신. 테스트 값은 문서용 예약 대역 IP와 존재할 수 없는 연락처, 계좌, 카드, 주소만 씁니다. */
@ExtendWith(OutputCaptureExtension.class)
class BulkLedgerCsvStagingParserV3Test {
    /** 실행마다 새로 만드는 합성 테스트 키입니다. 같은 실행 안에서만 토큰 비교에 씁니다. */
    private static final String KEY = "test-only-" + UUID.randomUUID() + UUID.randomUUID();
    private static final String HEADER = "event_id,performance_id,reservation_reference,ticket_reference,account_reference,purchased_at,ticket_quantity,"
            + "quantity_unit,ticket_status,source_version";
    private final UUID tenant = UUID.randomUUID(), event = UUID.randomUUID(), performance = UUID.randomUUID();

    @Test
    void v2KeepsRejectingRawSignalColumns() {
        String csv = HEADER + ",ip_reference\n" + base("r1", "t1") + ",192.0.2.10\n";
        assertThatThrownBy(() -> parse("v2", csv, 1, new ArrayList<>()))
                .isInstanceOf(BulkLedgerCsvValidationException.class).hasMessage("BULK_CSV_HEADER_INVALID");
    }

    @Test
    void v2RowsCarryNoRawSignalBlockAndV3WithoutSignalColumnsIsStillValid() {
        List<BulkLedgerCsvStagingParser.ParsedRow> v2 = new ArrayList<>();
        parse("v2", HEADER + "\n" + base("r1", "t1") + "\n", 1, v2);
        assertThat(v2).singleElement().satisfies(row -> assertThat(row.signals()).isNull());

        List<BulkLedgerCsvStagingParser.ParsedRow> v3 = new ArrayList<>();
        parse("v3", HEADER + "\n" + base("r1", "t1") + "\n", 1, v3);
        assertThat(v3).singleElement().satisfies(row -> {
            assertThat(row.signals()).isNotNull();
            assertThat(row.signals().ipHmac()).isNull();
            assertThat(row.rowDigest()).isNotEqualTo(v2.getFirst().rowDigest());
        });
    }

    @Test
    void sameRawValueGivesSameTokenAndDifferentSignalKindsGiveDifferentTokens() {
        String header = HEADER + ",device_reference,delivery_address,recipient_phone,member_phone,ip_reference,refund_bank_code,refund_account_number,"
                + "payment_masked_card,payment_issuer_code,payment_method_type";
        String csv = header + "\n"
                + base("r1", "t1") + ",가상시 가상구 모의로 1,가상시 가상구 모의로 1,000-0000-0001,,192.0.2.10,999,000000000001,999999******0001,zz,card\n"
                + base("r2", "t2") + ", 가상시 가상구 모의로 1 ,가상시   가상구 모의로 1 (101동),+82 0-0000-0001,000-0000-0002,192.0.2.10,999,000-000-000-001,9999 99** **** 0001,ZZ,CARD\n";
        List<BulkLedgerCsvStagingParser.ParsedRow> rows = new ArrayList<>();
        parse("v3", csv, 2, rows);
        var first = rows.get(0).signals();
        var second = rows.get(1).signals();
        assertThat(second.deviceHmac()).isEqualTo(first.deviceHmac());
        assertThat(second.addressHmac()).isEqualTo(first.addressHmac());
        assertThat(second.ipHmac()).isEqualTo(first.ipHmac());
        assertThat(second.refundAccountHmac()).isEqualTo(first.refundAccountHmac());
        assertThat(second.paymentHmac()).isEqualTo(first.paymentHmac());
        // 같은 문자열 "가상시 가상구 모의로 1"이라도 기기 신호와 배송지 신호의 토큰은 다릅니다.
        assertThat(first.deviceHmac()).isNotEqualTo(first.addressHmac());
        assertThat(first.phoneSource()).isEqualTo("RECIPIENT");
        assertThat(second.phoneSource()).isEqualTo("RECIPIENT");
        assertThat(first.ipNetworkType()).isEqualTo("UNKNOWN");
        assertThat(first.ipNetworkTypeSource()).isEqualTo("UNKNOWN");
        assertThat(List.of(first.deviceHmac(), first.addressHmac(), first.ipHmac(), first.refundAccountHmac(), first.paymentHmac(), first.phoneHmac()))
                .allSatisfy(token -> assertThat(token).matches("[0-9a-f]{64}"));
    }

    @Test
    void phoneFallsBackToMemberAndNetworkTypeRecordsItsSource() {
        String header = HEADER + ",member_phone,ip_reference,ip_network_type";
        String csv = header + "\n" + base("r1", "t1") + ",000-0000-0003,198.51.100.7,corporate_edu\n"
                + base("r2", "t2") + ",000-0000-0003,203.0.113.9,\n";
        List<BulkLedgerCsvStagingParser.ParsedRow> rows = new ArrayList<>();
        var hashers = hashers(List.of("203.0.113.0/24=DATACENTER_PROXY"));
        new BulkLedgerCsvStagingParser().parse(stream(csv), "v3", BulkLedgerImportJob.Mode.FULL_SNAPSHOT, tenant, scopes(2), reservation(),
                hasher("TICKET"), hasher("ACCOUNT"), hashers, rows::add);
        assertThat(rows.get(0).signals().phoneSource()).isEqualTo("MEMBER");
        assertThat(rows.get(0).signals().phoneHmac()).isEqualTo(rows.get(1).signals().phoneHmac());
        assertThat(rows.get(0).signals().ipNetworkType()).isEqualTo("CORPORATE_EDU");
        assertThat(rows.get(0).signals().ipNetworkTypeSource()).isEqualTo("DECLARED");
        assertThat(rows.get(1).signals().ipNetworkType()).isEqualTo("DATACENTER_PROXY");
        assertThat(rows.get(1).signals().ipNetworkTypeSource()).isEqualTo("CLASSIFIED");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "ip_reference|2001:db8::1|BULK_CSV_IP_INVALID",
            "ip_reference|192.0.2.256|BULK_CSV_IP_INVALID",
            "ip_reference|192.0.2|BULK_CSV_IP_INVALID",
            "ip_reference|192.0.2.010|BULK_CSV_IP_INVALID",
            "ip_network_type|RESIDENTIAL|BULK_CSV_IP_NETWORK_TYPE_INVALID",
            "payment_masked_card|9999999999990001|BULK_CSV_PAYMENT_UNMASKED",
            "refund_bank_code|999|BULK_CSV_REFUND_ACCOUNT_INCOMPLETE",
            "recipient_phone|---|BULK_CSV_PHONE_INVALID"
    })
    void invalidRawSignalIsARowErrorWithoutTheRawValue(String column, String raw, String code, CapturedOutput output) {
        String csv = HEADER + "," + column + "\n" + base("r1", "t1") + "," + raw + "\n";
        Throwable thrown = catchParse(csv);
        assertThat(thrown).isInstanceOf(BulkLedgerCsvValidationException.class).hasMessage(code);
        assertThat(((BulkLedgerCsvValidationException) thrown).rowNumber()).isEqualTo(2);
        assertNoRaw(thrown, output, raw);
    }

    @Test
    void unmaskedCardWithSeparatorsIsStillRejected(CapturedOutput output) {
        String raw = "9999 9999 9999 0001";
        String csv = HEADER + ",payment_masked_card,payment_issuer_code,payment_method_type\n" + base("r1", "t1") + "," + raw + ",ZZ,CARD\n";
        Throwable thrown = catchParse(csv);
        assertThat(thrown).hasMessage("BULK_CSV_PAYMENT_UNMASKED");
        assertNoRaw(thrown, output, raw, "9999999999990001");
    }

    @Test
    void brokenCsvWithRawIpNeverEchoesTheSource(CapturedOutput output) {
        String raw = "192.0.2.77";
        // 닫히지 않은 따옴표는 CSV 문법 오류입니다. 원본 IP와 주소가 예외, 로그 어디에도 남지 않아야 합니다.
        String csv = HEADER + ",ip_reference,delivery_address\n" + base("r1", "t1") + "," + raw + ",\"가상시 가상구 모의로 7\n";
        Throwable thrown = catchParse(csv);
        assertThat(thrown).isInstanceOf(BulkLedgerCsvValidationException.class).hasMessage("BULK_CSV_INVALID_UTF8_OR_SYNTAX");
        assertNoRaw(thrown, output, raw, "모의로 7");

        String strayQuote = HEADER + ",ip_reference\n" + base("r1", "t1") + ",19\"2.0.2.78\n";
        Throwable second = catchParse(strayQuote);
        assertThat(second).isInstanceOf(BulkLedgerCsvValidationException.class);
        assertNoRaw(second, output, "2.0.2.78");
    }

    @Test
    void normalizerCanonicalizesBeforeHashing() {
        assertThat(BulkLedgerRawSignalNormalizer.ipv4(" 192.0.2.1 ", 2)).isEqualTo("192.0.2.1");
        assertThat(BulkLedgerRawSignalNormalizer.phone("+82 10-0000-0004", 2)).isEqualTo("01000000004");
        assertThat(BulkLedgerRawSignalNormalizer.phone("+82 010-0000-0004", 2)).isEqualTo("01000000004");
        assertThat(BulkLedgerRawSignalNormalizer.phone("010-0000-0004", 2)).isEqualTo("01000000004");
        assertThat(BulkLedgerRawSignalNormalizer.address("가상시　가상구  모의로 5 (모의동)", 2)).isEqualTo("가상시 가상구 모의로 5");
        assertThat(BulkLedgerRawSignalNormalizer.maskedCard("999999-******-0001", 2)).isEqualTo("999999******0001");
        assertThat(BulkLedgerRawSignalNormalizer.digits("000-000-0005", 2, "X")).isEqualTo("0000000005");
        assertThat(BulkLedgerRawSignalNormalizer.upper(" zz ")).isEqualTo("ZZ");
    }

    @Test
    void signalHashersMustShareTheStagingKeyVersion() {
        String csv = HEADER + "\n" + base("r1", "t1") + "\n";
        var mismatched = new BulkLedgerRawSignalHashers(sig(BulkLedgerRawSignalHashers.DEVICE, "v2"), sig(BulkLedgerRawSignalHashers.IP, "v2"),
                sig(BulkLedgerRawSignalHashers.ADDRESS, "v2"), sig(BulkLedgerRawSignalHashers.PHONE, "v2"), sig(BulkLedgerRawSignalHashers.PAYMENT, "v2"),
                sig(BulkLedgerRawSignalHashers.REFUND_ACCOUNT, "v2"), new Track1IpNetworkClassifier(List.of()));
        assertThatThrownBy(() -> new BulkLedgerCsvStagingParser().parse(stream(csv), "v3", BulkLedgerImportJob.Mode.FULL_SNAPSHOT, tenant, scopes(1),
                reservation(), hasher("TICKET"), hasher("ACCOUNT"), mismatched, row -> { })).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new BulkLedgerRawSignalHashers(sig(BulkLedgerRawSignalHashers.DEVICE, "v1"), sig(BulkLedgerRawSignalHashers.IP, "v2"),
                sig(BulkLedgerRawSignalHashers.ADDRESS, "v1"), sig(BulkLedgerRawSignalHashers.PHONE, "v1"), sig(BulkLedgerRawSignalHashers.PAYMENT, "v1"),
                sig(BulkLedgerRawSignalHashers.REFUND_ACCOUNT, "v1"), new Track1IpNetworkClassifier(List.of()))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void classifierUsesLongestPrefixAndRejectsInvalidConfiguration() {
        var classifier = new Track1IpNetworkClassifier(List.of("198.51.100.0/24=RESIDENTIAL", "198.51.100.128/25=MOBILE_CGNAT", " "));
        assertThat(classifier.classify("198.51.100.1")).contains("RESIDENTIAL");
        assertThat(classifier.classify("198.51.100.200")).contains("MOBILE_CGNAT");
        assertThat(classifier.classify("203.0.113.1")).isEmpty();
        assertThatThrownBy(() -> new Track1IpNetworkClassifier(List.of("198.51.100.0/33=RESIDENTIAL"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new Track1IpNetworkClassifier(List.of("198.51.100.0/24=HOME"))).isInstanceOf(IllegalStateException.class);
    }

    private void assertNoRaw(Throwable thrown, CapturedOutput output, String... raws) {
        StringWriter trace = new StringWriter();
        thrown.printStackTrace(new PrintWriter(trace));
        for (String raw : raws) {
            assertThat(trace.toString()).doesNotContain(raw);
            assertThat(String.valueOf(thrown.getMessage())).doesNotContain(raw);
            assertThat(output.getAll()).doesNotContain(raw);
        }
    }

    private Throwable catchParse(String csv) {
        try {
            parse("v3", csv, 1, new ArrayList<>());
        } catch (Throwable thrown) {
            return thrown;
        }
        throw new AssertionError("expected a v3 row error");
    }

    private String base(String reservation, String ticket) {
        return event + "," + performance + "," + reservation + "," + ticket + ",account-" + ticket + ",2026-11-13T10:00:00Z,1,TICKET,ACTIVE,source-v1";
    }

    private void parse(String schema, String csv, long rows, List<BulkLedgerCsvStagingParser.ParsedRow> sink) {
        new BulkLedgerCsvStagingParser().parse(stream(csv), schema, BulkLedgerImportJob.Mode.FULL_SNAPSHOT, tenant, scopes(rows), reservation(),
                hasher("TICKET"), hasher("ACCOUNT"), "v3".equals(schema) ? hashers(List.of()) : null, sink::add);
    }

    private List<BulkLedgerImportScope> scopes(long rows) {
        return List.of(new BulkLedgerImportScope(UUID.randomUUID(), tenant, event, performance, LocalDateTime.now(), rows, null, "cursor-1", null, 1L));
    }

    private static ByteArrayInputStream stream(String csv) { return new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)); }
    private static TenantPurposeReferenceHasher reservation() { return new TenantPurposeReferenceHasher(KEY, "v1"); }
    private static TenantPurposeReferenceHasher hasher(String purpose) { return new TenantPurposeReferenceHasher(KEY, purpose, "v1"); }
    private static TenantPurposeReferenceHasher sig(String purpose, String version) { return new TenantPurposeReferenceHasher(KEY, purpose, version); }

    static BulkLedgerRawSignalHashers hashers(List<String> classes) {
        return new BulkLedgerRawSignalHashers(sig(BulkLedgerRawSignalHashers.DEVICE, "v1"), sig(BulkLedgerRawSignalHashers.IP, "v1"),
                sig(BulkLedgerRawSignalHashers.ADDRESS, "v1"), sig(BulkLedgerRawSignalHashers.PHONE, "v1"), sig(BulkLedgerRawSignalHashers.PAYMENT, "v1"),
                sig(BulkLedgerRawSignalHashers.REFUND_ACCOUNT, "v1"), new Track1IpNetworkClassifier(classes));
    }
}
