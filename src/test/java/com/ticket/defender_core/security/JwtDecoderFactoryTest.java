package com.ticket.defender_core.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class JwtDecoderFactoryTest {
    private static final String ISSUER = "https://issuer.example.test/admin";
    private static final String JWKS = "https://issuer.example.test/jwks";
    private static final String AUDIENCE = "ticket-defender-admin";

    private KeyPair trustedKey;
    private JwtDecoder decoder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() throws Exception {
        trustedKey = newRsaKey();
        RestTemplate rest = new RestTemplate();
        server = MockRestServiceServer.bindTo(rest).build();
        RSAKey jwk = new RSAKey.Builder((RSAPublicKey) trustedKey.getPublic()).keyID("trusted").build();
        server.expect(manyTimes(), requestTo(JWKS))
                .andRespond(withSuccess(new JWKSet(jwk).toString(), MediaType.APPLICATION_JSON));
        decoder = JwtDecoderFactory.create(ISSUER, JWKS, AUDIENCE, null, rest);
    }

    @Test
    void acceptsOnlySignedUnexpiredTokenForIssuerAndAudience() throws Exception {
        String valid = token(trustedKey, ISSUER, AUDIENCE, Instant.now().plusSeconds(300), null);
        assertThat(decoder.decode(valid).getSubject()).isEqualTo("admin-1");

        assertThatThrownBy(() -> decoder.decode(token(newRsaKey(), ISSUER, AUDIENCE,
                Instant.now().plusSeconds(300), null))).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(token(trustedKey, "https://other.example.test", AUDIENCE,
                Instant.now().plusSeconds(300), null))).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(token(trustedKey, ISSUER, "wrong-audience",
                Instant.now().plusSeconds(300), null))).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(token(trustedKey, ISSUER, null,
                Instant.now().plusSeconds(300), null))).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(token(trustedKey, ISSUER, AUDIENCE,
                Instant.now().minusSeconds(300), null))).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(token(trustedKey, ISSUER, AUDIENCE,
                null, null))).isInstanceOf(JwtException.class);
        server.verify();
    }

    @Test
    void serviceTokenMustHaveConfiguredClientId() throws Exception {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer serviceServer = MockRestServiceServer.bindTo(rest).build();
        RSAKey jwk = new RSAKey.Builder((RSAPublicKey) trustedKey.getPublic()).keyID("trusted").build();
        serviceServer.expect(manyTimes(), requestTo(JWKS))
                .andRespond(withSuccess(new JWKSet(jwk).toString(), MediaType.APPLICATION_JSON));
        JwtDecoder serviceDecoder = JwtDecoderFactory.create(ISSUER, JWKS, "ticket-defender-analysis",
                "ingest-client", rest);

        assertThat(serviceDecoder.decode(token(trustedKey, ISSUER, "ticket-defender-analysis",
                Instant.now().plusSeconds(300), "ingest-client")).getSubject()).isEqualTo("admin-1");
        assertThatThrownBy(() -> serviceDecoder.decode(token(trustedKey, ISSUER, "ticket-defender-analysis",
                Instant.now().plusSeconds(300), "other-client"))).isInstanceOf(JwtException.class);
        serviceServer.verify();
    }

    @Test
    void missingProviderRejectsTokensAndPartialOrInsecureConfigurationFailsStartup() {
        assertThatThrownBy(() -> JwtDecoderFactory.create("", "", "", null).decode("anything"))
                .isInstanceOf(BadJwtException.class);
        assertThatThrownBy(() -> JwtDecoderFactory.create(ISSUER, "", AUDIENCE, null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwtDecoderFactory.create(ISSUER, "http://issuer.example.test/jwks", AUDIENCE, null))
                .isInstanceOf(IllegalStateException.class);
    }

    private static String token(KeyPair key, String issuer, String audience, Instant expiresAt,
            String clientId) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("admin-1");
        if (audience != null) {
            claims.audience(audience);
        }
        if (expiresAt != null) {
            claims.expirationTime(Date.from(expiresAt));
        }
        if (clientId != null) {
            claims.claim("client_id", clientId);
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("trusted").build(),
                claims.build());
        jwt.sign(new RSASSASigner(key.getPrivate()));
        return jwt.serialize();
    }

    private static KeyPair newRsaKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
}
