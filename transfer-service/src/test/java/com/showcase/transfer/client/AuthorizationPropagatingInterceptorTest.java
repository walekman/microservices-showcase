package com.showcase.transfer.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Runs the real interceptor inside a real RestClient (MockRestServiceServer only replaces the
 * transport), so this proves the whole path: AccountClient tags the request, RestClient hands
 * the tag to the interceptor, and the interceptor picks the token.
 */
class AuthorizationPropagatingInterceptorTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String BASE_URL = "http://account-service:8081";
    private static final String USER_TOKEN = "user-token";
    private static final String SERVICE_TOKEN = "service-token";

    private MockRestServiceServer server;
    private AccountClient accountClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL)
                .requestInterceptor(new AuthorizationPropagatingInterceptor(stubServiceTokenManager()));
        server = MockRestServiceServer.bindTo(builder).build();
        accountClient = new AccountClient(builder.build(), new ObjectMapper());

        // A live saga request: the customer's own JWT is on the calling thread.
        Jwt userJwt = Jwt.withTokenValue(USER_TOKEN).header("alg", "none").subject("ada")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(userJwt));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void debitRelaysTheCallersOwnToken() {
        // Account's debit ownership check depends on seeing the customer's own subject.
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID + "/debit"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + USER_TOKEN))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        accountClient.debit(ACCOUNT_ID, new BigDecimal("10.00"), "EUR", "t-1:debit");

        server.verify();
    }

    @Test
    void creditUsesTheServiceTokenEvenWithACallerOnTheThread() {
        // Account requires account-crediter for credit, which no customer holds -- relaying the
        // customer's token here would 403 every live transfer's credit leg.
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID + "/credit"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + SERVICE_TOKEN))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        accountClient.credit(ACCOUNT_ID, new BigDecimal("10.00"), "EUR", "t-1:credit");

        server.verify();
    }

    private static OAuth2AuthorizedClientManager stubServiceTokenManager() {
        ClientRegistration registration = ClientRegistration.withRegistrationId("transfer-service")
                .clientId("transfer-service")
                .clientSecret("test-secret")
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri("http://stub-token-uri.invalid/token")
                .build();
        OAuth2AccessToken accessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, SERVICE_TOKEN,
                Instant.now(), Instant.now().plusSeconds(3600));
        OAuth2AuthorizedClient authorizedClient = new OAuth2AuthorizedClient(registration, "transfer-service", accessToken);
        return authorizeRequest -> authorizedClient;
    }
}
