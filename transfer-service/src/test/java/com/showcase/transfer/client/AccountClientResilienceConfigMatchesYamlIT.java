package com.showcase.transfer.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-checks AccountClientResilienceTest's hand-copied CircuitBreakerConfig/RetryConfig
 * against the real, Spring-bound configuration built from application.yml -- the two can
 * drift silently otherwise, since nothing previously compared them (see docs/roadmap.md's
 * Phase 3 final-review finding). Boots the real ApplicationContext and reads the actual
 * "accountService" instance out of the auto-configured registries, rather than parsing the
 * YAML file directly, so this also catches a typo'd property name that Spring's binder
 * would otherwise silently ignore.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class AccountClientResilienceConfigMatchesYamlIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private RetryRegistry retryRegistry;

    @Test
    void circuitBreakerConfigMatchesTheHandCopyInAccountClientResilienceTest() {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("accountService");
        CircuitBreakerConfig config = circuitBreaker.getCircuitBreakerConfig();

        assertThat(config.getSlidingWindowSize()).isEqualTo(30);
        assertThat(config.getMinimumNumberOfCalls()).isEqualTo(15);
        assertThat(config.getFailureRateThreshold()).isEqualTo(50.0f);
        assertThat(config.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(Duration.ofSeconds(10).toMillis());
        assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(3);
        assertThat(config.getRecordExceptionPredicate().test(new AccountServiceUnavailableException("x"))).isTrue();
        assertThat(config.getIgnoreExceptionPredicate().test(new AccountRejectedException("CODE", "x"))).isTrue();
    }

    @Test
    void retryConfigMatchesTheHandCopyInAccountClientResilienceTest() {
        Retry retry = retryRegistry.retry("accountService");
        RetryConfig config = retry.getRetryConfig();

        assertThat(config.getMaxAttempts()).isEqualTo(3);
        assertThat(config.getIntervalBiFunction().apply(1, null)).isEqualTo(Duration.ofMillis(200).toMillis());
        // getExceptionPredicate() is the composed retry-exceptions-minus-ignore-exceptions
        // predicate Resilience4j actually evaluates -- exactly what retry-exceptions plus
        // ignore-exceptions in application.yml resolves to.
        assertThat(config.getExceptionPredicate().test(new AccountServiceUnavailableException("x"))).isTrue();
        assertThat(config.getExceptionPredicate().test(new AccountRejectedException("CODE", "x"))).isFalse();
    }
}
