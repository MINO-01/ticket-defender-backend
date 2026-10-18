package com.ticket.defender_core.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MacroEvidenceKeyTest {

    /** 같은 식별 조합은 항상 같은 키를 만듭니다. */
    @Test
    @DisplayName("동일한 공연, 예매, 계정 조합은 고정 길이 키를 생성한다")
    void create_returnsStableSha256Key() {
        // when
        String first = MacroEvidenceKey.create("공연-1", "예약-12", "계정-3");
        String second = MacroEvidenceKey.create("공연-1", "예약-12", "계정-3");

        // then
        assertThat(first).isEqualTo(second).hasSize(64).matches("[0-9a-f]{64}");
    }

    /** 필드 경계가 다른 조합은 서로 다른 키를 만듭니다. */
    @Test
    @DisplayName("길이 접두 인코딩으로 서로 다른 식별 조합을 구분한다")
    void create_distinguishesDifferentFieldBoundaries() {
        // when
        String first = MacroEvidenceKey.create("event", "ab", "c");
        String second = MacroEvidenceKey.create("event", "a", "bc");

        // then
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void scopedKeysSeparateTenantsEventsAndLegacyRows() {
        UUID tenantA = UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID tenantB = UUID.fromString("22222222-2222-4222-8222-222222222222");
        UUID eventA = UUID.fromString("33333333-3333-4333-8333-333333333333");
        UUID eventB = UUID.fromString("44444444-4444-4444-8444-444444444444");

        String key = MacroEvidenceKey.create(tenantA, eventA, "reservation-1", "account-1");
        assertThat(key).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(key).isEqualTo(MacroEvidenceKey.create(tenantA, eventA, "reservation-1", "account-1"));
        assertThat(key).isNotEqualTo(MacroEvidenceKey.create(tenantB, eventA, "reservation-1", "account-1"));
        assertThat(key).isNotEqualTo(MacroEvidenceKey.create(tenantA, eventB, "reservation-1", "account-1"));
        assertThat(key).isNotEqualTo(MacroEvidenceKey.create("shared-event", "reservation-1", "account-1"));
    }
}
