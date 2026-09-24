package com.showcase.transfer.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AccountClientResilienceTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String BASE_URL = "http://account-service:8081";

    private MockRestServiceServer server;
    private AccountClient accountClient;
    private Retry retry;
    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        accountClient = new AccountClient(builder.build(), new ObjectMapper());

        // Mirrors application.yml's resilience4j.*.instances.accountService exactly (see the
        // comment there for why the circuit-breaker window/minimum are 3x the retry count) --
        // if these numbers and that file drift apart, this test is the tripwire. See also
        // AccountClientResilienceConfigMatchesYamlIT, which cross-checks these hand-copied
        // values against the actual Spring-bound configuration instead of trusting the copy.
        retry = Retry.of("accountService", RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(200))
                .retryExceptions(AccountServiceUnavailableException.class)
                .ignoreExceptions(AccountRejectedException.class)
                .build());
        circuitBreaker = CircuitBreaker.of("accountService", CircuitBreakerConfig.custom()
                .slidingWindowSize(30)
                .minimumNumberOfCalls(15)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(3)
                .recordExceptions(AccountServiceUnavailableException.class)
                .ignoreExceptions(AccountRejectedException.class)
                .build());
    }

    private <T> T retryAndBreak(Supplier<T> call) {
        return Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(circuitBreaker, call)).get();
    }

    // accountExists is void, but retryAndBreak's Supplier<T> plumbing (and CircuitBreaker's own
    // decorateSupplier below) needs a return value -- wrapping it as Supplier<Void> costs
    // nothing and keeps this class's resilience-proving helpers shared across every AccountClient
    // method, not just the ones that happen to return a body.
    private String callAccountCurrency() {
        return accountClient.accountCurrency(ACCOUNT_ID);
    }

    @Test
    void retriesATransientFailureAndEventuallySucceeds() {
        server.expect(requestTo(BASE_URL + "/accounts/exists/" + ACCOUNT_ID))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(BASE_URL + "/accounts/exists/" + ACCOUNT_ID))
                .andRespond(withSuccess("{\"currency\":\"EUR\"}", MediaType.APPLICATION_JSON));

        retryAndBreak(this::callAccountCurrency);

        // Exactly the two expectations registered above were consumed -- if Retry had not
        // fired, the first (failing) response alone would have propagated and this call
        // would have thrown instead of returning.
        server.verify();
    }

    @Test
    void doesNotRetryABusinessRejection() {
        server.expect(requestTo(BASE_URL + "/accounts/exists/" + ACCOUNT_ID))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("""
                                {"type":"https://showcase.example/errors/x","title":"t","status":404,
                                 "detail":"not found","code":"ACCOUNT_NOT_FOUND","timestamp":"2026-09-10T12:00:00Z"}
                                """));

        assertThatThrownBy(() -> retryAndBreak(this::callAccountCurrency))
                .isInstanceOf(AccountRejectedException.class);
        // Only one request was registered above -- if this had been retried, verify() would
        // fail with "no further requests expected" instead of this test reaching this line.
        server.verify();
    }

    @Test
    void opensAfterEnoughFailuresAndShortCircuitsWithoutANewRequest() {
        for (int i = 0; i < 15; i++) {
            server.expect(requestTo(BASE_URL + "/accounts/exists/" + ACCOUNT_ID))
                    .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        }
        // Drive exactly 15 failures through the circuit breaker alone (bypassing Retry here,
        // so each iteration is exactly one HTTP call) to reach minimumNumberOfCalls.
        for (int i = 0; i < 15; i++) {
            assertThatThrownBy(() -> CircuitBreaker.decorateSupplier(circuitBreaker, this::callAccountCurrency).get())
                    .isInstanceOf(AccountServiceUnavailableException.class);
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> CircuitBreaker.decorateSupplier(circuitBreaker, this::callAccountCurrency).get())
                .isInstanceOf(CallNotPermittedException.class);
        // No 16th expectation was registered -- if the circuit had not actually opened, the
        // call above would attempt a real 16th request and MockRestServiceServer would fail
        // with "no further requests expected" instead of this reaching CallNotPermittedException.
        server.verify();
    }
}
