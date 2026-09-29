package com.ticket.defender_core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    /**
     * Spring Context에 RestClient.Builder를 수동으로 등록하여
     * FastApiAdapter 등에서 의존성 주입(@Autowired)을 받을 수 있게 합니다.
     */
    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
}
