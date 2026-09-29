package com.ticket.defender_core.adapter.out.api;

import com.ticket.defender_core.adapter.out.api.dto.FastApiClusterResponse;
import com.ticket.defender_core.adapter.out.api.dto.MacroAnalysisRequest;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Collections;
import java.util.List;

@Slf4j
@Component
public class FastApiAdapter {

    private final RestClient restClient;

    @Value("${FASTAPI_URL:http://localhost:8000}")
    private String fastApiUrl;

    public FastApiAdapter(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    /**
     * [Track 1] 그래프 기반 매크로 군집 분석 요청
     * 서킷 브레이커 발동 시 analyzeFallback 메서드로 흐름이 넘어감
     */
    @CircuitBreaker(name = "fastApi", fallbackMethod = "analyzeFallback")
    public List<FastApiClusterResponse> requestMacroAnalysis(MacroAnalysisRequest request) {
        return restClient.post()
                .uri(fastApiUrl + "/api/v1/clusters/analyze")
                .body(request)
                .retrieve()
                .body(new ParameterizedTypeReference<List<FastApiClusterResponse>>() {});
    }

    /**
     * 서킷 브레이커 Fallback 메서드 (장애 격리용)
     * FastAPI 서버가 죽었거나 타임아웃이 발생하면 이 메서드를 실행.
     */
    public List<FastApiClusterResponse> analyzeFallback(MacroAnalysisRequest request, Throwable t) {
        log.error("[CircuitBreaker OPEN] FastAPI 분석 서버 장애 또는 지연 발생. 원인: {}", t.getMessage());
        return Collections.emptyList();
    }
}