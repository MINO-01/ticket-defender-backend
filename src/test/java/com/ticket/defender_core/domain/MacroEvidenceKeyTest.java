package com.ticket.defender_core.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
