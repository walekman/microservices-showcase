package com.showcase.gateway;

import com.showcase.gateway.support.TestSecurityConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestSecurityConfig.class)
class GatewayRoutingIT {

    private static HttpServer fakeTransferService;
    private static HttpServer fakeAccountService;

    /** Captures the Authorization header the fake upstream actually received. */
    private static final AtomicReference<String> receivedAuthorizationHeader = new AtomicReference<>();

    @DynamicPropertySource
    static void upstreamUrls(DynamicPropertyRegistry registry) throws IOException {
        fakeTransferService = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        fakeTransferService.createContext("/transfers/123", exchange -> {
            receivedAuthorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"id\":\"123\",\"status\":\"COMPLETED\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        fakeTransferService.start();
        registry.add("transfer-service.base-url",
                () -> "http://localhost:" + fakeTransferService.getAddress().getPort());

        fakeAccountService = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        fakeAccountService.createContext("/accounts/999", exchange -> {
            byte[] body = ("""
                    {"type":"https://showcase.example/errors/account-not-found","title":"Account not found",
                     "status":404,"detail":"Account not found: 999","code":"ACCOUNT_NOT_FOUND",
                     "timestamp":"2026-09-16T12:00:00Z"}
                    """).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/problem+json");
            exchange.sendResponseHeaders(404, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        fakeAccountService.start();
        registry.add("account-service.base-url",
                () -> "http://localhost:" + fakeAccountService.getAddress().getPort());
    }

    @AfterAll
    static void stopFakes() {
        if (fakeTransferService != null) {
            fakeTransferService.stop(0);
        }
        if (fakeAccountService != null) {
            fakeAccountService.stop(0);
        }
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void authenticateAsCustomer() {
        TestSecurityConfig.authenticateAsCustomer(restTemplate);
    }

    @Test
    void proxiesToTransferServiceUnchanged() {
        ResponseEntity<String> response = restTemplate.getForEntity("/transfers/123", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"COMPLETED\"");
    }

    /**
     * The load-bearing claim in docs/phase-7-auth-keycloak-jwt.md's Token Propagation section:
     * every downstream Resource Server check depends on the Gateway actually forwarding the
     * Authorization header, not just returning the right status code.
     */
    @Test
    void forwardsTheAuthorizationHeaderToTheUpstream() {
        restTemplate.getForEntity("/transfers/123", String.class);
        assertThat(receivedAuthorizationHeader.get()).isEqualTo("Bearer " + TestSecurityConfig.CUSTOMER_TOKEN);
    }

    @Test
    void proxiesProblemJsonErrorBodyUnchanged() {
        ResponseEntity<String> response = restTemplate.getForEntity("/accounts/999", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType()).hasToString("application/problem+json");
        assertThat(response.getBody()).contains("\"code\":\"ACCOUNT_NOT_FOUND\"");
    }

    @Test
    void debitPathIsUnreachableThroughTheGateway() {
        ResponseEntity<String> response = restTemplate.postForEntity("/accounts/999/debit", null, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void creditPathIsUnreachableThroughTheGateway() {
        ResponseEntity<String> response = restTemplate.postForEntity("/accounts/999/credit", null, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void fraudBlocklistIsUnreachableThroughTheGateway() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/fraud/blocklist/" + UUID.randomUUID(), HttpMethod.PUT, null, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
