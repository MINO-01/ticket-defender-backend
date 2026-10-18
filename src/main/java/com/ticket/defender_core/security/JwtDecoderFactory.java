package com.ticket.defender_core.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.web.client.RestOperations;

import java.net.URI;

/** Builds decoders for externally issued access tokens. No issuer configuration means no accepted tokens. */
public final class JwtDecoderFactory {

    private JwtDecoderFactory() {
    }

    public static JwtDecoder create(String issuer, String jwkSetUri, String audience, String clientId) {
        return create(issuer, jwkSetUri, audience, clientId, null);
    }

    static JwtDecoder create(String issuer, String jwkSetUri, String audience,
            String clientId, RestOperations restOperations) {
        boolean configured = present(issuer) || present(jwkSetUri) || present(audience) || present(clientId);
        if (!configured) {
            return token -> {
                throw new BadJwtException("External authentication provider is not configured");
            };
        }
        if (!present(issuer) || !present(jwkSetUri) || !present(audience)) {
            throw new IllegalStateException("JWT issuer, JWK set URI and audience must be configured together");
        }
        requireHttps(issuer);
        requireHttps(jwkSetUri);

        var builder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256);
        if (restOperations != null) {
            builder.restOperations(restOperations);
        }
        NimbusJwtDecoder decoder = builder.build();
        decoder.setJwtValidator(validator(issuer, audience, clientId));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> validator(String issuer, String audience, String clientId) {
        OAuth2TokenValidator<Jwt> requiredClaims = jwt -> {
            if (jwt.getExpiresAt() == null || !present(jwt.getSubject())
                    || jwt.getAudience() == null || !jwt.getAudience().contains(audience)
                    || (present(clientId) && !clientId.equals(jwt.getClaim("client_id")))) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Required JWT claim is invalid", null));
            }
            return OAuth2TokenValidatorResult.success();
        };
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), requiredClaims);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static void requireHttps(String value) {
        URI uri = URI.create(value);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !present(uri.getHost())) {
            throw new IllegalStateException("JWT issuer and JWK set URI must use HTTPS");
        }
    }
}
