package com.showcase.fraud.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Deliberately not the single spring.security.oauth2.resourceserver.jwt.issuer-uri property:
 * this service reaches Keycloak internally as http://keycloak:8080 (Docker Compose's internal
 * DNS) but tokens carry an externally-facing "iss" claim (http://localhost:8180 -- what a
 * human/host client actually requested a token from). A single issuer-uri property would force
 * one of the two addresses to fail to resolve. The JWKS fetch (an actual network call, for
 * verifying the token's signature) uses the internal address; the issuer check (a string
 * compare against the token's iss claim, never dereferenced as a URL) uses the external one.
 * See docs/phase-7-auth-keycloak-jwt.md's Design Decisions for why.
 */
@Configuration
public class JwtDecoderConfig {

    @Bean
    public JwtDecoder jwtDecoder(KeycloakProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuerUri()));
        return decoder;
    }
}
