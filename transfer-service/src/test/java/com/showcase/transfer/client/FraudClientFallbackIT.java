package com.showcase.transfer.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boots the real, Spring-managed {@link FraudClient} bean -- @CircuitBreaker/@Retry active,
 * fallback wired -- unlike FraudClientTest, which constructs FraudClient directly and never
 * exercises the AOP proxy. Same reason as AccountClientFallbackIT: TransferServiceTest and
 * CompensationSchedulerTest mock FraudClient entirely, so neither exercises the fallback
 * passthrough that a real business rejection depends on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@Import(StubServiceTokenTestConfig.class)
class FraudClientFallbackIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static HttpServer fakeFraudService;

    @DynamicPropertySource
    static void fraudServiceUrl(DynamicPropertyRegistry registry) throws IOException {
        fakeFraudService = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        fakeFraudService.createContext("/fraud-check", exchange -> {
            byte[] body = ("""
                    {"type":"https://showcase.example/errors/account-blocked","title":"Account blocked",
                     "status":422,"detail":"Account is blocklisted: %s","code":"ACCOUNT_BLOCKED",
                     "timestamp":"2026-09-16T12:00:00Z"}
                    """.formatted(ACCOUNT_ID)).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/problem+json");
            exchange.sendResponseHeaders(422, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        fakeFraudService.start();
        registry.add("fraud-service.base-url", () -> "http://localhost:" + fakeFraudService.getAddress().getPort());
    }

    @AfterAll
    static void stopFakeFraudService() {
        if (fakeFraudService != null) {
            fakeFraudService.stop(0);
        }
    }

    @Autowired
    private FraudClient fraudClient;

    @Test
    void aDefinitiveRejectionPassesThroughTheRealAopProxyUnchanged() {
        assertThatThrownBy(() -> fraudClient.check(ACCOUNT_ID))
                .isInstanceOf(FraudRejectedException.class)
                .hasMessageContaining("Account is blocklisted");
    }
}
