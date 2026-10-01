package com.ticket.defender_core.global.converter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StringListConverterTest {

    private final StringListConverter converter = new StringListConverter();

    @Test
    @DisplayName("[CSV 변환 테스트] 쉼표(,)나 따옴표(\")가 포함된 URL도 깨지지 않고 복원되어야 한다")
    void roundTripWithCommaAndQuotes() {
        // given
        List<String> originalList = List.of(
                "https://example.com/normal.png",
                "https://example.com/image,with,commas.png",
                "https://example.com/image\"with\"quotes.png"
        );

        // when
        String dbData = converter.convertToDatabaseColumn(originalList);

        // then
        List<String> restoredList = converter.convertToEntityAttribute(dbData);

        assertThat(restoredList).hasSize(3);
        assertThat(restoredList.get(0)).isEqualTo("https://example.com/normal.png");
        assertThat(restoredList.get(1)).isEqualTo("https://example.com/image,with,commas.png");
        assertThat(restoredList.get(2)).isEqualTo("https://example.com/image\"with\"quotes.png");

        assertThat(restoredList).containsExactlyElementsOf(originalList);
    }
}