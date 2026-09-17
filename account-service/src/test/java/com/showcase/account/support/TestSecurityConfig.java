package com.showcase.account.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Stands in for a real Keycloak in real-HTTP-round-trip tests (TestRestTemplate, not
 * MockMvc's jwt() post-processor -- that only works against the servlet-level Authentication,
 * not a genuine bearer header). The decoder accepts three canned token strings: CUSTOMER_TOKEN
 * carries every capability permission, mirroring what a real "customer" composite-role token
 * would contain; CUSTOMER2_TOKEN is a second, distinct customer (same authorities, different
 * subject) so a real end-to-end request/response/repository round trip can prove ownership
 * denial across two different real people, not just against a mocked service; and ADMIN_TOKEN
 * carries account-admin only, mirroring the admin persona Phase 7b introduced. There's no real
 * signature to verify since no real Keycloak is involved in these tests. See
 * docs/phase-7-auth-keycloak-jwt.md and docs/phase-7b-account-ownership-authorization.md.
 */
@TestConfiguration
public class TestSecurityConfig {

    public static final String CUSTOMER_TOKEN = "test-customer-token";
    public static final String CUSTOMER2_TOKEN = "test-customer2-token";
    public static final String ADMIN_TOKEN = "test-admin-token";

    // A real Keycloak sub is always a UUID (Account's ownership check now parses it as one via
    // UUID.fromString) -- a non-UUID stand-in like the old "test-customer" broke every
    // authenticated call in AccountControllerIT the moment Phase 7b started reading this claim.
    public static final UUID CUSTOMER_SUBJECT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    public static final UUID CUSTOMER2_SUBJECT = UUID.fromString("33333333-3333-3333-3333-333333333333");

    // Named differently from the production JwtDecoderConfig's "jwtDecoder" bean -- a full
    // @SpringBootTest DOES scan that real @Configuration class too, and two bean definitions
    // sharing one name is a hard registration error regardless of @Primary (which only resolves
    // ambiguity between distinctly-named candidates at an injection point, not a same-name
    // registration collision).
    @Bean
    @Primary
    public JwtDecoder stubJwtDecoder() {
        return token -> {
            if (CUSTOMER_TOKEN.equals(token)) {
                return Jwt.withTokenValue(token)
                        .header("alg", "none")
                        .subject(CUSTOMER_SUBJECT.toString())
                        .claim("realm_access", Map.of("roles",
                                List.of("transfer-executor", "account-reader", "account-editor", "fraud-checker")))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(3600))
                        .build();
            }
            if (CUSTOMER2_TOKEN.equals(token)) {
                return Jwt.withTokenValue(token)
                        .header("alg", "none")
                        .subject(CUSTOMER2_SUBJECT.toString())
                        .claim("realm_access", Map.of("roles",
                                List.of("transfer-executor", "account-reader", "account-editor", "fraud-checker")))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(3600))
                        .build();
            }
            if (ADMIN_TOKEN.equals(token)) {
                return Jwt.withTokenValue(token)
                        .header("alg", "none")
                        .subject(UUID.randomUUID().toString())
                        .claim("realm_access", Map.of("roles", List.of("account-admin")))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(3600))
                        .build();
            }
            throw new JwtException("Unrecognised test token: " + token);
        };
    }

    /**
     * A RestTemplateBuilder bean is not reliably picked up by TestRestTemplateContextCustomizer
     * in every Boot version/configuration -- found live (gateway-service's equivalent test kept
     * getting 401s with the bean-based approach). Calling this from a @BeforeEach directly
     * mutates the already-built TestRestTemplate instead, which is guaranteed to take effect.
     * Idempotent: the underlying RestTemplate bean is shared/cached across this test class's
     * methods, so a naive @BeforeEach would otherwise add a duplicate interceptor per test.
     */
    public static void authenticateAsCustomer(TestRestTemplate restTemplate) {
        var interceptors = restTemplate.getRestTemplate().getInterceptors();
        boolean alreadyAdded = interceptors.stream().anyMatch(BearerAuthInterceptor.class::isInstance);
        if (!alreadyAdded) {
            interceptors.add(new BearerAuthInterceptor(CUSTOMER_TOKEN));
        }
    }

    /**
     * Only sets the header when the request doesn't already carry one -- lets a single test
     * override the class-wide customer identity with an explicit Authorization header (e.g. the
     * admin token) for the one call that needs a different persona, without mutating the shared
     * interceptor list other test methods rely on.
     */
    private record BearerAuthInterceptor(String token) implements ClientHttpRequestInterceptor {
        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
                throws IOException {
            if (!request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)) {
                request.getHeaders().setBearerAuth(token);
            }
            return execution.execute(request, body);
        }
    }
}
