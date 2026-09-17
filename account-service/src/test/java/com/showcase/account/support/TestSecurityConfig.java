package com.showcase.account.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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

/**
 * Stands in for a real Keycloak in real-HTTP-round-trip tests (TestRestTemplate, not
 * MockMvc's jwt() post-processor -- that only works against the servlet-level Authentication,
 * not a genuine bearer header). The decoder accepts exactly one canned token string and
 * returns a Jwt carrying every capability permission, mirroring what a real "customer"
 * composite-role token would contain; there's no real signature to verify since no real
 * Keycloak is involved in these tests. See docs/phase-7-auth-keycloak-jwt.md.
 */
@TestConfiguration
public class TestSecurityConfig {

    public static final String CUSTOMER_TOKEN = "test-customer-token";

    // Named differently from the production JwtDecoderConfig's "jwtDecoder" bean -- a full
    // @SpringBootTest DOES scan that real @Configuration class too, and two bean definitions
    // sharing one name is a hard registration error regardless of @Primary (which only resolves
    // ambiguity between distinctly-named candidates at an injection point, not a same-name
    // registration collision).
    @Bean
    @Primary
    public JwtDecoder stubJwtDecoder() {
        return token -> {
            if (!CUSTOMER_TOKEN.equals(token)) {
                throw new JwtException("Unrecognised test token: " + token);
            }
            return Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .subject("test-customer")
                    .claim("realm_access", Map.of("roles",
                            List.of("transfer-executor", "account-reader", "account-editor", "fraud-checker")))
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .build();
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

    private record BearerAuthInterceptor(String token) implements ClientHttpRequestInterceptor {
        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
                throws IOException {
            request.getHeaders().setBearerAuth(token);
            return execution.execute(request, body);
        }
    }
}
