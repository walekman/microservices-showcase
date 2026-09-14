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

        // Mirrors application.yml's resilience4j.*.instances.accountService exactly --
        // if these numbers and that file drift apart, this test is the tripwire.
        retry = Retry.of("accountService", RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(200))
                .retryExceptions(AccountServiceUnavailableException.class)
                .ignoreExceptions(AccountRejectedException.class)
                .build());
        circuitBreaker = CircuitBreaker.of("accountService", CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
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

    @Test
    void retriesATransientFailureAndEventuallySucceeds() {
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID))
                .andRespond(withSuccess("""
                        {"id":"%s","ownerName":"Ada Lovelace","balance":100.00,"createdAt":"2026-09-10T12:00:00Z"}
                        """.formatted(ACCOUNT_ID), MediaType.APPLICATION_JSON));

        AccountView account = retryAndBreak(() -> accountClient.getAccount(ACCOUNT_ID));

        assertThat(account.balance()).isEqualByComparingTo("100.00");
        // Exactly the two expectations registered above were consumed -- if Retry had not
        // fired, the first (failing) response alone would have propagated and this call
        // would have thrown instead of returning.
        server.verify();
    }

    @Test
    void doesNotRetryABusinessRejection() {
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("""
                                {"type":"https://showcase.example/errors/x","title":"t","status":404,
                                 "detail":"not found","code":"ACCOUNT_NOT_FOUND","timestamp":"2026-09-10T12:00:00Z"}
                                """));

        assertThatThrownBy(() -> retryAndBreak(() -> accountClient.getAccount(ACCOUNT_ID)))
                .isInstanceOf(AccountRejectedException.class);
        // Only one request was registered above -- if this had been retried, verify() would
        // fail with "no further requests expected" instead of this test reaching this line.
        server.verify();
    }

    @Test
    void opensAfterEnoughFailuresAndShortCircuitsWithoutANewRequest() {
        for (int i = 0; i < 5; i++) {
            server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID))
                    .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        }
        // Drive exactly 5 failures through the circuit breaker alone (bypassing Retry here,
        // so each iteration is exactly one HTTP call) to reach minimumNumberOfCalls.
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> CircuitBreaker.decorateSupplier(circuitBreaker,
                    () -> accountClient.getAccount(ACCOUNT_ID)).get())
                    .isInstanceOf(AccountServiceUnavailableException.class);
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> CircuitBreaker.decorateSupplier(circuitBreaker,
                () -> accountClient.getAccount(ACCOUNT_ID)).get())
                .isInstanceOf(CallNotPermittedException.class);
        // No 6th expectation was registered -- if the circuit had not actually opened, the
        // call above would attempt a real 6th request and MockRestServiceServer would fail
        // with "no further requests expected" instead of this reaching CallNotPermittedException.
        server.verify();
    }
}
