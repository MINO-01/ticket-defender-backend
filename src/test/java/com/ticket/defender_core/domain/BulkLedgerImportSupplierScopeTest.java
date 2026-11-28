package com.ticket.defender_core.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BulkLedgerImportSupplierScopeTest {
    @Test
    void resolutionIsImmutableAndKeepsCreationFlagsAcrossWorkerRetry() {
        var scope = new BulkLedgerImportSupplierScope(UUID.randomUUID(), UUID.randomUUID(), "SUP-E-1", "SUP-P-1",
                "합성 공연", LocalDateTime.parse("2026-12-01T10:00:00"), 4, LocalDateTime.parse("2026-11-13T00:00:00"),
                1, null, "cursor-1", null, null);
        UUID event = UUID.randomUUID(), performance = UUID.randomUUID();

        scope.resolve(event, performance, true, true);
        scope.resolve(event, performance, false, false);

        assertThat(scope.getEventId()).isEqualTo(event);
        assertThat(scope.getPerformanceId()).isEqualTo(performance);
        assertThat(scope.isEventCreated()).isTrue();
        assertThat(scope.isPerformanceCreated()).isTrue();
        assertThatThrownBy(() -> scope.resolve(UUID.randomUUID(), UUID.randomUUID(), false, false))
                .isInstanceOf(IllegalStateException.class);
    }
}
