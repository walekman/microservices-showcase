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
