package com.ticket.defender_core.application.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 설정된 IPv4 CIDR 표로 망 유형을 판정합니다. 기본 표는 비어 있으므로 모든 주소가 판정 없음이 됩니다.
 * 항목 형식은 {@code 203.0.113.0/24=CORPORATE_EDU}이며, 가장 긴 접두가 우선합니다.
 */
public final class Track1IpNetworkClassifier {
    private record Rule(long network, long mask, int prefix, String type) { }
    private final List<Rule> rules;

    public Track1IpNetworkClassifier(List<String> entries) {
        List<Rule> parsed = new ArrayList<>();
        for (String entry : entries == null ? List.<String>of() : entries) {
            if (entry == null || entry.isBlank()) continue;
            String[] parts = entry.strip().split("=", -1);
            String[] cidr = parts.length == 2 ? parts[0].strip().split("/", -1) : new String[0];
            if (cidr.length != 2) throw new IllegalStateException("Invalid track1.ip-network-classes entry");
            final int prefix;
            try { prefix = Integer.parseInt(cidr[1]); } catch (NumberFormatException invalid) { throw new IllegalStateException("Invalid track1.ip-network-classes prefix"); }
            String type = parts[1].strip();
            if (prefix < 0 || prefix > 32 || !BulkLedgerRawSignalNormalizer.NETWORK_TYPES.contains(type))
                throw new IllegalStateException("Invalid track1.ip-network-classes entry");
            final String address;
            try { address = BulkLedgerRawSignalNormalizer.ipv4(cidr[0], 0); }
            catch (BulkLedgerCsvValidationException invalid) { throw new IllegalStateException("Invalid track1.ip-network-classes address"); }
            long mask = prefix == 0 ? 0 : (0xffffffffL << (32 - prefix)) & 0xffffffffL;
            parsed.add(new Rule(value(address) & mask, mask, prefix, type));
        }
        parsed.sort((left, right) -> Integer.compare(right.prefix(), left.prefix()));
        this.rules = List.copyOf(parsed);
    }

    /** 정규화된 IPv4 문자열을 받아 일치하는 망 유형을 돌려줍니다. */
    public Optional<String> classify(String canonicalIpv4) {
        long address = value(canonicalIpv4);
        for (Rule rule : rules) if ((address & rule.mask()) == rule.network()) return Optional.of(rule.type());
        return Optional.empty();
    }

    private static long value(String canonicalIpv4) {
        long result = 0;
        for (String octet : canonicalIpv4.split("\\.")) result = (result << 8) | Integer.parseInt(octet);
        return result;
    }
}
