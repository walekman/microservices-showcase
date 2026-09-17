package com.showcase.gateway.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Stands in for a real Keycloak in real-HTTP-round-trip tests (TestRestTemplate, not MockMvc's
 * jwt() post-processor -- see docs/phase-7-auth-keycloak-jwt.md). The decoder accepts any
 * bearer string of the form "test-token:role1,role2,..." and returns a Jwt carrying exactly
 * those authorities, so a single stub can exercise every permission combination a test needs
 * without a real Keycloak.
 */
@TestConfiguration
public class TestSecurityConfig {

    // RFC 6750's bearer-token syntax only allows [A-Za-z0-9-._~+/]+ (plus "=" padding) --
    // Spring Security's BearerTokenAuthenticationFilter rejects anything else as "malformed"
    // BEFORE the JwtDecoder ever runs. Found live: a ":"/","-delimited scheme returned 401
    // with no decoder invocation at all, which looked identical to "no header sent".
    private static final String PREFIX = "testtoken.";
    private static final String DELIMITER = "+";

    /** All four capability permissions -- mirrors what a real "customer" composite grants. */
    public static final String CUSTOMER_TOKEN =
            tokenWithAuthorities("transfer-executor", "account-reader", "account-editor", "fraud-checker");

    public static String tokenWithAuthorities(String... authorities) {
        return PREFIX + String.join(DELIMITER, authorities);
    }

    // Named differently from the production JwtDecoderConfig's "jwtDecoder" bean -- a full
    // @SpringBootTest (unlike a @WebMvcTest slice) DOES scan that real @Configuration class too,
    // and two bean definitions sharing one name is a hard registration error regardless of
    // @Primary (which only resolves ambiguity between distinctly-named candidates at an
    // injection point, not a same-name registration collision).
    @Bean
    @Primary
    public JwtDecoder stubJwtDecoder() {
        return token -> {
            if (!token.startsWith(PREFIX)) {
                throw new JwtException("Unrecognised test token: " + token);
            }
            String rolesPart = token.substring(PREFIX.length());
            List<String> roles = rolesPart.isBlank()
                    ? List.of() : Arrays.asList(rolesPart.split("\\" + DELIMITER));
            return Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .subject("test-subject")
                    .claim("realm_access", Map.of("roles", roles))
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .build();
        };
    }

    /**
     * Adds a default Authorization header to every request the given TestRestTemplate makes,
     * unless the test already added one to a specific request. Idempotent -- safe to call from
     * a @BeforeEach without accumulating duplicate interceptors across test methods, since the
     * underlying RestTemplate bean is shared (cached) across this test class's methods.
     */
    public static void authenticateAsCustomer(TestRestTemplate restTemplate) {
        var interceptors = restTemplate.getRestTemplate().getInterceptors();
        boolean alreadyAdded = interceptors.stream().anyMatch(BearerAuthInterceptor.class::isInstance);
        if (!alreadyAdded) {
            interceptors.add(new BearerAuthInterceptor(CUSTOMER_TOKEN));
        }
    }

    private record BearerAuthInterceptor(String token)
            implements org.springframework.http.client.ClientHttpRequestInterceptor {
        @Override
        public org.springframework.http.client.ClientHttpResponse intercept(
                org.springframework.http.HttpRequest request, byte[] body,
                org.springframework.http.client.ClientHttpRequestExecution execution) throws java.io.IOException {
            request.getHeaders().setBearerAuth(token);
            return execution.execute(request, body);
        }
    }
}
