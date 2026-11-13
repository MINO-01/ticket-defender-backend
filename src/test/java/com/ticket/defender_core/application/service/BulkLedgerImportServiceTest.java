package com.ticket.defender_core.application.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** BL-01: scope 순서는 계약 의미가 아니므로 요청 digest가 순서 변화에 안정적이어야 한다. */
class BulkLedgerImportServiceTest {

    @Test
    void canonicalDigestIsStableWhenScopesAreReordered() {
        UUID eventA = UUID.randomUUID();
        UUID eventB = UUID.randomUUID();
        UUID performanceA = UUID.randomUUID();
        UUID performanceB = UUID.randomUUID();
        var first = List.of(new BulkLedgerImportService.ScopeDigest(eventA, performanceA, "2026-11-01T00:00:00Z", null, null, 10L),
                new BulkLedgerImportService.ScopeDigest(eventB, performanceB, "2026-11-01T00:00:00Z", null, null, 20L));
        var reversed = List.of(first.get(1), first.get(0));

        assertThat(BulkLedgerImportService.requestDigest("v1", "SYNTHETIC", "batch-1", "FULL_SNAPSHOT", "CSV",
                "a".repeat(64), 100L, 30L, true, first))
                .isEqualTo(BulkLedgerImportService.requestDigest("v1", "SYNTHETIC", "batch-1", "FULL_SNAPSHOT", "CSV",
                        "a".repeat(64), 100L, 30L, true, reversed));
    }

    @Test
    void canonicalDigestSeparatesCursorBoundaries() {
        UUID event = UUID.randomUUID(); UUID performance = UUID.randomUUID();
        var first = List.of(new BulkLedgerImportService.ScopeDigest(event, performance, null, "a:b", "c", 1L));
        var second = List.of(new BulkLedgerImportService.ScopeDigest(event, performance, null, "a", "b:c", 1L));

        assertThat(BulkLedgerImportService.requestDigest("v1", "SYNTHETIC", "batch-1", "CHANGE_FEED", "CSV",
                "a".repeat(64), 1L, 1L, false, first))
                .isNotEqualTo(BulkLedgerImportService.requestDigest("v1", "SYNTHETIC", "batch-1", "CHANGE_FEED", "CSV",
                        "a".repeat(64), 1L, 1L, false, second));
    }
}
