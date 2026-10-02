package com.ticket.defender_core.adapter.out.api;

import com.ticket.defender_core.adapter.out.api.dto.FastApiClusterResponse;
import com.ticket.defender_core.adapter.out.api.dto.MacroAnalysisRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FastApiAdapterTest {

    private FastApiAdapter fastApiAdapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder realBuilder = RestClient.builder();
        fastApiAdapter = new FastApiAdapter(realBuilder);
    }

    @Test
    @DisplayName("서킷 브레이커(Fallback) 메서드가 호출되면, 에러를 던지지 않고 빈 리스트를 반환해야 한다.")
    void circuitBreaker_fallback_test() {
        // given
        MacroAnalysisRequest request = new MacroAnalysisRequest(
                List.of("payment_hash_1"),
                List.of("address_hash_1")
        );

        // when
        List<FastApiClusterResponse> response = fastApiAdapter.analyzeFallback(request, new RuntimeException("Connection Refused Test Error"));

        // then
        assertThat(response).isNotNull();
        assertThat(response).isEmpty();
    }
}