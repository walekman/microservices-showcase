package com.showcase.transfer.config;

import com.showcase.transfer.client.AuthorizationPropagatingInterceptor;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/**
 * Wires transfer-service's own machine identity (a client-credentials grant against Keycloak)
 * and attaches {@link AuthorizationPropagatingInterceptor} to every RestClient this module
 * builds. {@link AuthorizedClientServiceOAuth2AuthorizedClientManager}, not the web-request-bound
 * manager Spring Security autoconfigures for OAuth2 login flows, is the standard shape for a
 * client-credentials grant fetched outside of any inbound request -- exactly
 * CompensationScheduler's situation. See docs/phase-7-auth-keycloak-jwt.md's Design Decisions.
 */
@Configuration
public class ServiceOAuth2ClientConfig {

    @Bean
    public OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientService authorizedClientService) {
        OAuth2AuthorizedClientProvider authorizedClientProvider = OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials()
                .build();
        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                        clientRegistrationRepository, authorizedClientService);
        manager.setAuthorizedClientProvider(authorizedClientProvider);
        return manager;
    }

    @Bean
    public AuthorizationPropagatingInterceptor authorizationPropagatingInterceptor(
            OAuth2AuthorizedClientManager authorizedClientManager) {
        return new AuthorizationPropagatingInterceptor(authorizedClientManager);
    }

    /**
     * Spring Boot applies every {@link RestClientCustomizer} bean to any injected
     * {@code RestClient.Builder} -- this reaches both AccountClientConfig's and
     * FraudClientConfig's builders without editing either.
     */
    @Bean
    public RestClientCustomizer authorizationPropagatingRestClientCustomizer(
            AuthorizationPropagatingInterceptor interceptor) {
        return builder -> builder.requestInterceptor(interceptor);
    }
}
