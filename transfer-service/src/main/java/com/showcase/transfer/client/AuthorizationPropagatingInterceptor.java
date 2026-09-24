package com.showcase.transfer.client;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
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
 * <p><b>Service-identity requests</b> -- a request tagged with {@link #USE_SERVICE_IDENTITY}
 * always carries the service token, even with a caller on the thread. Account's credit is the
 * one such call: it requires {@code account-crediter}, which only transfer-service's machine
 * identity holds, because credit has no ownership check and a customer able to call it could
 * create money (a former open item, now closed).
 *
 * <p>See docs/phase-7-auth-keycloak-jwt.md's Design Decisions for why both paths exist.
 */
public class AuthorizationPropagatingInterceptor implements ClientHttpRequestInterceptor {

    static final String SERVICE_REGISTRATION_ID = "transfer-service";

    /** RestClient request attribute: when {@code true}, send the service token, never the caller's. */
    public static final String USE_SERVICE_IDENTITY = AuthorizationPropagatingInterceptor.class.getName() + ".USE_SERVICE_IDENTITY";

    private final OAuth2AuthorizedClientManager authorizedClientManager;

    public AuthorizationPropagatingInterceptor(OAuth2AuthorizedClientManager authorizedClientManager) {
        this.authorizedClientManager = authorizedClientManager;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        boolean serviceIdentity = Boolean.TRUE.equals(request.getAttributes().get(USE_SERVICE_IDENTITY));
        request.getHeaders().setBearerAuth(serviceIdentity ? fetchServiceToken() : resolveToken());
        return execution.execute(request, body);
    }

    private String resolveToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            return jwtAuthentication.getToken().getTokenValue();
        }
        // Only fall back to the service's own (more privileged than any single permission,
        // holding both account-editor and fraud-checker) token when there is genuinely no
        // request-bound identity to relay -- null (the background-sweep case this fallback
        // exists for) or an anonymous authentication (a permitAll path, e.g. /actuator/health,
        // where no SecurityConfig disables anonymous auth). Anything else is an authentication
        // type this interceptor was never designed to handle; escalating it to the service
        // token by default would silently hand out money-moving credentials to whatever that
        // unexpected caller turns out to be. Found in code review, not written test-first.
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
            return fetchServiceToken();
        }
        throw new IllegalStateException("Unexpected Authentication type on the calling thread: "
                + authentication.getClass() + " -- refusing to escalate to the transfer-service machine token");
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
