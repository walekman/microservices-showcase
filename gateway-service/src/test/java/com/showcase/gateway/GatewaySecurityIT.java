package com.showcase.gateway;

import com.showcase.gateway.support.TestSecurityConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the REAL per-route authorization matrix (see docs/phase-7-auth-keycloak-jwt.md's
 * Per-Endpoint Authorization Matrix) -- not just that Gateway rejects an unauthenticated
 * request, but that each route requires its own specific permission. Gateway has no
 * database/Kafka, so this needs no Testcontainers/Docker.
 *
 * Every upstream points at one fake that answers 200 to anything, so a request the Gateway
 * admits comes back 200 and one it rejects comes back with the Gateway's own 401/403. Without
 * it the routes proxied to the default localhost:808x URLs -- a running dev stack answered 401
 * (its real decoder rejects the stub token), no stack meant a 5xx -- and the "admits" tests
 * could only assert "not 403", which a Gateway-side 401 also satisfies.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestSecurityConfig.class)
class GatewaySecurityIT {

    private static HttpServer fakeUpstream;

    @DynamicPropertySource
    static void upstreamUrls(DynamicPropertyRegistry registry) throws IOException {
        fakeUpstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        fakeUpstream.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        fakeUpstream.start();
        String url = "http://localhost:" + fakeUpstream.getAddress().getPort();
        registry.add("transfer-service.base-url", () -> url);
        registry.add("account-service.base-url", () -> url);
        registry.add("fx-service.base-url", () -> url);
    }

    @AfterAll
    static void stopFakeUpstream() {
        if (fakeUpstream != null) {
            fakeUpstream.stop(0);
        }
    }

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
    void transfersRouteAcceptsTransferExecutorAuthority() {
        ResponseEntity<String> response = requestWithAuthorities("/transfers/123", HttpMethod.GET, "transfer-executor");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void accountsGetRouteAcceptsAccountReaderAuthority() {
        ResponseEntity<String> response = requestWithAuthorities("/accounts", HttpMethod.GET, "account-reader");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // Phase 7b's account-admin/transfer-admin (docs/phase-7b-account-ownership-authorization.md)
    // must clear the Gateway's own gate too -- it independently re-checks authority before
    // proxying, same as every other route here. Found missing in code review: the Gateway's
    // matchers weren't updated alongside account-service's/transfer-service's, so an admin
    // token 403'd here before ever reaching the service meant to authorize it.

    @Test
    void accountsGetRouteAcceptsAccountAdminAuthority() {
        ResponseEntity<String> response = requestWithAuthorities("/accounts", HttpMethod.GET, "account-admin");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void transfersListRouteAcceptsTransferAdminAuthority() {
        ResponseEntity<String> response = requestWithAuthorities("/transfers", HttpMethod.GET, "transfer-admin");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void accountsPostRouteReturns403WithoutAccountEditorAuthority() {
        ResponseEntity<String> response = requestWithAuthorities("/accounts", HttpMethod.POST, "account-reader");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void accountsPostRouteAcceptsAccountEditorAuthority() {
        ResponseEntity<String> response = requestWithAuthorities("/accounts", HttpMethod.POST, "account-editor");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void actuatorHealthNeedsNoToken() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void accountSummaryRouteAcceptsAnyAuthenticatedCaller() {
        ResponseEntity<String> response = requestWithAuthorities("/accounts/123/summary", HttpMethod.GET, "transfer-executor");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void transfersMineRouteAcceptsTransferExecutorAuthority() {
        ResponseEntity<String> response = requestWithAuthorities("/transfers/mine", HttpMethod.GET, "transfer-executor");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void fxRatesRouteReturns403WithoutFxReaderAuthority() {
        ResponseEntity<String> response = requestWithAuthorities("/fx/rates?base=PLN&quote=EUR", HttpMethod.GET, "transfer-executor");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void fxRatesRouteAcceptsFxReaderAuthority() {
        ResponseEntity<String> response = requestWithAuthorities("/fx/rates?base=PLN&quote=EUR", HttpMethod.GET, "fx-reader");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
