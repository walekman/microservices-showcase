package com.showcase.transfer.client;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.io.IOException;

/**
 * Attaches a bearer token to every outbound call AccountClient/FraudClient make, picking
 * between two sources depending on which thread is calling:
 *
 * <p><b>Live saga path</b> -- an inbound Gateway-forwarded user request has already been
 * validated by this service's own Resource Server filter, populating a
 * {@link JwtAuthenticationToken} on {@link SecurityContextHolder}. Relaying that same token
 * is what lets Account's debit/credit and Fraud's fraud-check see the *calling user's own*
 * authorities (e.g. "account-editor" via the "customer" composite) even though no human ever
 * calls those endpoints directly.
 *
 * <p><b>Background sweep path</b> -- {@code CompensationScheduler}'s {@code @Scheduled}
 * methods run on their own thread, with no inbound request and therefore no
 * {@link SecurityContextHolder} entry at all. There, this falls back to a client-credentials
 * token for transfer-service's own machine identity, fetched (and cached/refreshed) via
 * {@link OAuth2AuthorizedClientManager}.
 *
 * <p>See docs/phase-7-auth-keycloak-jwt.md's Design Decisions for why both paths exist.
 */
public class AuthorizationPropagatingInterceptor implements ClientHttpRequestInterceptor {

    static final String SERVICE_REGISTRATION_ID = "transfer-service";

    private final OAuth2AuthorizedClientManager authorizedClientManager;

    public AuthorizationPropagatingInterceptor(OAuth2AuthorizedClientManager authorizedClientManager) {
        this.authorizedClientManager = authorizedClientManager;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        request.getHeaders().setBearerAuth(resolveToken());
        return execution.execute(request, body);
    }

    private String resolveToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            return jwtAuthentication.getToken().getTokenValue();
        }
        return fetchServiceToken();
    }

    private String fetchServiceToken() {
        OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest
                .withClientRegistrationId(SERVICE_REGISTRATION_ID)
                .principal(SERVICE_REGISTRATION_ID)
                .build();
        OAuth2AuthorizedClient authorizedClient = authorizedClientManager.authorize(authorizeRequest);
        if (authorizedClient == null || authorizedClient.getAccessToken() == null) {
            throw new IllegalStateException(
                    "Unable to obtain a " + SERVICE_REGISTRATION_ID + " access token for an outbound call");
        }
        return authorizedClient.getAccessToken().getTokenValue();
    }
}
