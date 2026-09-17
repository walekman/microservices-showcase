package com.showcase.transfer.client;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;

import java.time.Instant;

/**
 * Stands in for a real Keycloak client-credentials token fetch. These tests boot the real,
 * Spring-managed AccountClient/FraudClient beans (see e.g. AccountClientFallbackIT's javadoc)
 * with no inbound request -- exactly AuthorizationPropagatingInterceptor's fallback path
 * (CompensationScheduler's situation), which would otherwise try a real network call to a
 * Keycloak token endpoint that doesn't exist in these tests. The fake upstream HTTP servers
 * these tests use don't parse or validate the Authorization header at all, so any canned
 * token value is sufficient.
 */
@TestConfiguration
public class StubServiceTokenTestConfig {

    @Bean
    @Primary
    public OAuth2AuthorizedClientManager stubAuthorizedClientManager() {
        ClientRegistration registration = ClientRegistration.withRegistrationId("transfer-service")
                .clientId("transfer-service")
                .clientSecret("test-secret")
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri("http://stub-token-uri.invalid/realms/showcase/protocol/openid-connect/token")
                .build();
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, "test-service-token",
                Instant.now(), Instant.now().plusSeconds(3600));
        OAuth2AuthorizedClient authorizedClient =
                new OAuth2AuthorizedClient(registration, "transfer-service", accessToken);
        return authorizeRequest -> authorizedClient;
    }
}
