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
 * Boots the real, Spring-managed {@link AccountClient} bean -- @CircuitBreaker/@Retry
 * annotations active, fallback methods wired -- unlike AccountClientTest and
 * AccountClientResilienceTest, which construct AccountClient directly and so never
 * exercise the AOP proxy at all. Points account-service.base-url at a tiny real JDK
 * HttpServer (no new test dependency, and no fragile Spring bean-override needed to get
 * MockRestServiceServer bound to whatever RestClient.Builder the real bean actually uses).
 *
 * <p>This exists because a real bug (found via live docker compose verification, not by
 * writing this test first) could not have been caught any other way:
 * {@code CompensationSchedulerTest} mocks AccountClient entirely, so it never touched the
 * fallback methods either. Resilience4j's fallbackMethod, once specified, is invoked for
 * every exception the decorated method throws -- including AccountRejectedException,
 * which is configured as an ignored exception in application.yml and was expected to
 * propagate untouched. Without AccountClient's explicit passthrough, a definitive
 * rejection was silently miscategorized as AccountServiceUnavailableException, which
 * broke CompensationScheduler's compensateSource() branch: it never fired, so a stranded
 * debit was never reversed -- the scheduler just logged "will retry next sweep" forever.
 */
// Plain @SpringBootTest (MOCK web environment, the default), not webEnvironment = NONE: found
// live once this phase added a SecurityConfig -- Spring Security's HttpSecurity bean is only
// registered for a WebApplicationContext, so NONE (no web context at all) made
// SecurityConfig.securityFilterChain(HttpSecurity, ...) fail to construct with
// "No qualifying bean of type HttpSecurity". MOCK doesn't bind a real port either, so this
// test's actual behavior (calling AccountClient's methods directly) is unaffected.
@SpringBootTest
@Testcontainers
@Import(StubServiceTokenTestConfig.class)
class AccountClientFallbackIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static HttpServer fakeAccountService;

    @DynamicPropertySource
    static void accountServiceUrl(DynamicPropertyRegistry registry) throws IOException {
        fakeAccountService = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        fakeAccountService.createContext("/accounts/exists/" + ACCOUNT_ID, exchange -> {
            byte[] body = ("""
                    {"type":"https://showcase.example/errors/account-not-found","title":"Account not found",
                     "status":404,"detail":"Account not found: %s","code":"ACCOUNT_NOT_FOUND",
                     "timestamp":"2026-09-10T12:00:00Z"}
                    """.formatted(ACCOUNT_ID)).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/problem+json");
            exchange.sendResponseHeaders(404, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        fakeAccountService.createContext("/accounts/" + ACCOUNT_ID + "/debit", rejectingHandler());
        fakeAccountService.createContext("/accounts/" + ACCOUNT_ID + "/credit", rejectingHandler());
        fakeAccountService.start();
        registry.add("account-service.base-url",
                () -> "http://localhost:" + fakeAccountService.getAddress().getPort());
    }

    /** Rejects every debit/credit with a definitive, non-transient business rejection. */
    private static com.sun.net.httpserver.HttpHandler rejectingHandler() {
        return exchange -> {
            byte[] body = """
                    {"type":"https://showcase.example/errors/insufficient-funds","title":"Insufficient funds",
                     "status":422,"detail":"not enough money","code":"INSUFFICIENT_FUNDS",
                     "timestamp":"2026-09-10T12:00:00Z"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/problem+json");
            exchange.sendResponseHeaders(422, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        };
    }

    @AfterAll
    static void stopFakeAccountService() {
        if (fakeAccountService != null) {
            fakeAccountService.stop(0);
        }
    }

    @Autowired
    private AccountClient accountClient;

    @Test
    void aDefinitiveRejectionPassesThroughTheRealAopProxyUnchanged() {
        // Asserting the exact type and code is itself conclusive proof the fake backend was
        // reached: a real connection failure would surface as
        // AccountServiceUnavailableException instead, never this specific rejection code.
        assertThatThrownBy(() -> accountClient.accountCurrency(ACCOUNT_ID))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(AccountRejectedException.class))
                .extracting(AccountRejectedException::getCode)
                .isEqualTo("ACCOUNT_NOT_FOUND");
    }

    /**
     * debitCreditFallback is the fallback CompensationScheduler actually depends on --
     * accountCurrency's fallback above proves nothing about it, since Resilience4j wires a
     * fallbackMethod per decorated method, not per class. Without this test's own explicit
     * passthrough coverage, the exact Task 6 bug (a definitive rejection silently
     * miscategorized as AccountServiceUnavailableException) could recur here undetected --
     * see this class's javadoc and docs/roadmap.md's Phase 3 final-review finding.
     */
    @Test
    void debitsDefinitiveRejectionPassesThroughTheRealAopProxyUnchanged() {
        assertThatThrownBy(() -> accountClient.debit(ACCOUNT_ID, new java.math.BigDecimal("40.00"), "EUR", "fallback-it:debit"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(AccountRejectedException.class))
                .extracting(AccountRejectedException::getCode)
                .isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void creditsDefinitiveRejectionPassesThroughTheRealAopProxyUnchanged() {
        assertThatThrownBy(() -> accountClient.credit(ACCOUNT_ID, new java.math.BigDecimal("40.00"), "EUR", "fallback-it:credit"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(AccountRejectedException.class))
                .extracting(AccountRejectedException::getCode)
                .isEqualTo("INSUFFICIENT_FUNDS");
    }
}
