package com.showcase.gateway;

import com.showcase.gateway.support.TestSecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the REAL per-route authorization matrix (see docs/phase-7-auth-keycloak-jwt.md's
 * Per-Endpoint Authorization Matrix) -- not just that Gateway rejects an unauthenticated
 * request, but that each route requires its own specific permission. Gateway has no
 * database/Kafka, so this needs no Testcontainers/Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestSecurityConfig.class)
class GatewaySecurityIT {

    @Autowired
    private TestRestTemplate restTemplate;

    private ResponseEntity<String> requestWithAuthorities(String path, HttpMethod method, String... authorities) {
        HttpHeaders headers = new HttpHeaders();
        if (authorities != null) {
            headers.setBearerAuth(TestSecurityConfig.tokenWithAuthorities(authorities));
        }
        return restTemplate.exchange(path, method, new HttpEntity<>(headers), String.class);
    }

    @Test
    void transfersRouteReturns401WithNoToken() {
        ResponseEntity<String> response = restTemplate.getForEntity("/transfers/123", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void transfersRouteReturns403WithoutTransferExecutorAuthority() {
        ResponseEntity<String> response = requestWithAuthorities("/transfers/123", HttpMethod.GET, "account-reader");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void accountsGetRouteReturns403WithoutAccountReaderAuthority() {
        ResponseEntity<String> response = requestWithAuthorities("/accounts", HttpMethod.GET, "transfer-executor");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // Phase 7b's account-admin/transfer-admin (docs/phase-7b-account-ownership-authorization.md)
    // must clear the Gateway's own gate too -- it independently re-checks authority before
    // proxying, same as every other route here. Found missing in code review: the Gateway's
    // matchers weren't updated alongside account-service's/transfer-service's, so an admin
    // token 403'd here before ever reaching the service meant to authorize it. Asserts
    // "not FORBIDDEN" rather than a specific success status, since this class runs with no real
    // downstream to route to (see GatewayRoutingIT for that) -- what matters here is which gate
    // rejected the request, not what a real proxied response would look like.

    @Test
    void accountsGetRouteAcceptsAccountAdminAuthority() {
        ResponseEntity<String> response = requestWithAuthorities("/accounts", HttpMethod.GET, "account-admin");
        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void transfersListRouteAcceptsTransferAdminAuthority() {
        ResponseEntity<String> response = requestWithAuthorities("/transfers", HttpMethod.GET, "transfer-admin");
        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void accountsPostRouteReturns403WithoutAccountEditorAuthority() {
        ResponseEntity<String> response = requestWithAuthorities("/accounts", HttpMethod.POST, "account-reader");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void actuatorHealthNeedsNoToken() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
