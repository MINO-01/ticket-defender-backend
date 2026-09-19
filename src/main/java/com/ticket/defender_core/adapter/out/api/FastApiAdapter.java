package com.ticket.defender_core.adapter.out.api;

import com.ticket.defender_core.adapter.in.web.dto.AgentAnalysisRequest;
import com.ticket.defender_core.adapter.out.api.dto.FastApiClusterResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Collections;

@Slf4j
@Component
public class FastApiAdapter {

    private final RestClient restClient;

    public FastApiAdapter(@Value("${fastapi.url:http://localhost:8000}") String fastApiUrl) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(5));

        this.restClient = RestClient.builder()
                .baseUrl(fastApiUrl)
                .requestFactory(factory)
                .build();
    }

    @CircuitBreaker(name = "fastApi", fallbackMethod = "analyzeFallback")
    public FastApiClusterResponse requestFraudAnalysis(AgentAnalysisRequest request) {
        log.info("FastAPI 분석 서버로 {} 건의 해시 데이터 분석을 요청합니다.", request.tickets().size());

        return restClient.post()
                .uri("/api/v1/clusters/payment")
                .body(request)
                .retrieve()
                .body(FastApiClusterResponse.class);
    }

    public FastApiClusterResponse analyzeFallback(AgentAnalysisRequest request, Throwable t) {
        log.error("[장애 발생] FastAPI 서버 통신 실패. 서킷 브레이커가 작동하여 빈 결과를 반환합니다. 원인: {}", t.getMessage());
        // 장애 시 시스템을 다운시키지 않고, 안전하게 빈 리스트 응답
        return new FastApiClusterResponse("fallback", "분석 서버 지연으로 임시 중단됨", Collections.emptyList());
    }
}