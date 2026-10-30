package com.ticket.defender_core.config;

import com.ticket.defender_core.security.JwtDecoderFactory;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;

@Configuration
public class SecurityConfig {

    @Bean("adminJwtDecoder")
    JwtDecoder adminJwtDecoder(
            @Value("${ticket-defender.security.admin.issuer:}") String issuer,
            @Value("${ticket-defender.security.admin.jwk-set-uri:}") String jwkSetUri,
            @Value("${ticket-defender.security.admin.audience:}") String audience) {
        return JwtDecoderFactory.create(issuer, jwkSetUri, audience, null);
    }

    @Bean("analysisJwtDecoder")
    JwtDecoder analysisJwtDecoder(
            @Value("${ticket-defender.security.analysis.issuer:}") String issuer,
            @Value("${ticket-defender.security.analysis.jwk-set-uri:}") String jwkSetUri,
            @Value("${ticket-defender.security.analysis.audience:}") String audience,
            @Value("${ticket-defender.security.analysis.client-id:}") String clientId) {
        if ((issuer != null && !issuer.isBlank()) && (clientId == null || clientId.isBlank())) {
            throw new IllegalStateException("Analysis JWT client ID must be configured");
        }
        return JwtDecoderFactory.create(issuer, jwkSetUri, audience, clientId);
    }

    @Bean
    @Order(1)
    SecurityFilterChain analysisSecurity(HttpSecurity http,
            @Qualifier("analysisJwtDecoder") JwtDecoder decoder) throws Exception {
        return common(http)
                .securityMatcher("/api/v1/audits/analyze")
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.decoder(decoder)))
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain publicFanReportSecurity(HttpSecurity http,
            @Value("${ticket-defender.fan-report.enabled:false}") boolean enabled) throws Exception {
        return common(http)
                .securityMatcher("/api/v1/fan-reports", "/api/v1/fan-reports/lookup")
                .authorizeHttpRequests(requests -> {
                    if (enabled) {
                        requests.requestMatchers(HttpMethod.POST, "/api/v1/fan-reports",
                                "/api/v1/fan-reports/lookup").permitAll();
                    }
                    requests.anyRequest().denyAll();
                })
                .build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain adminSecurity(HttpSecurity http,
            @Qualifier("adminJwtDecoder") JwtDecoder decoder) throws Exception {
        return common(http)
                .authorizeHttpRequests(requests -> requests
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/audits/*/report").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/track1/clusters",
                                "/api/v1/admin/track1/clusters/detail").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/cases",
                                "/api/v1/cases/*/transitions",
                                "/api/v1/cases/*/evidence-snapshots",
                                "/api/v1/cases/*/report-versions",
                                "/api/v1/cases/*/evidence-verifications",
                                "/api/v1/cases/*/approval-requests",
                                "/api/v1/approval-requests/*/review-package",
                                "/api/v1/approval-requests/*/decision",
                                "/api/v1/approval-requests/*/withdraw").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/cases",
                                "/api/v1/cases/*", "/api/v1/cases/*/sources",
                                "/api/v1/cases/*/activities",
                                "/api/v1/cases/*/evidence-snapshots",
                                "/api/v1/cases/*/evidence-snapshots/*",
                                "/api/v1/cases/*/report-versions",
                                "/api/v1/cases/*/evidence-verifications",
                                "/api/v1/cases/*/integrity-evaluation",
                                "/api/v1/report-versions/*",
                                "/api/v1/approval-requests/*",
                                "/api/v1/approval-requests/*/review-package").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/api/v1/cases/*/assignee").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.decoder(decoder)))
                .build();
    }

    private HttpSecurity common(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(new BearerTokenAuthenticationEntryPoint())
                        .accessDeniedHandler((request, response, exception) -> response.sendError(403)));
    }
}
