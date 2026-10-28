package com.showcase.platform.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Deliberately not the single spring.security.oauth2.resourceserver.jwt.issuer-uri property:
 * services reach Keycloak internally as http://keycloak:8080 (Docker Compose's internal
 * DNS) but tokens carry an externally-facing "iss" claim (http://localhost:8180 -- what a
 * human/host client actually requested a token from). A single issuer-uri property would force
 * one of the two addresses to fail to resolve. The JWKS fetch (an actual network call, for
 * verifying the token's signature) uses the internal address; the issuer check (a string
 * compare against the token's iss claim, never dereferenced as a URL) uses the external one.
 * See docs/phase-7-auth-keycloak-jwt.md's Design Decisions for why.
 *
 * <p>Runs before Boot's resource-server auto-configuration and backs off when a service (or a
 * test's stub) declares its own {@link JwtDecoder}.
 */
@AutoConfiguration(beforeName = "org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration")
@ConditionalOnClass({JwtDecoder.class, NimbusJwtDecoder.class})
@EnableConfigurationProperties(KeycloakProperties.class)
public class KeycloakJwtAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public JwtDecoder jwtDecoder(KeycloakProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuerUri()));
        return decoder;
    }
}
