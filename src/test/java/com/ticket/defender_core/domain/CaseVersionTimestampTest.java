package com.ticket.defender_core.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CaseVersionTimestampTest {
    @Test
    void preparationTimestampAdvancesEvenWhenWritesShareTheSameInstant() {
        LocalDateTime now = LocalDateTime.parse("2026-11-25T10:15:30.123456");
        Case target = new Case(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Case.Type.GRAPH_CLUSTER, null, null, now);

        target.recordPreparation(now);
        LocalDateTime first = target.getUpdatedAt();
        target.recordPreparation(now);

        assertThat(first).isEqualTo(now.plus(1, ChronoUnit.MICROS));
        assertThat(target.getUpdatedAt()).isEqualTo(first.plus(1, ChronoUnit.MICROS));
    }
}
