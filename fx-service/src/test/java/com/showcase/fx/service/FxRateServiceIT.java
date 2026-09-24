package com.showcase.fx.service;

import com.showcase.fx.support.FakeRateProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** The real FxRateService against a real Redis and a fake provider: the cache behaviour end to end. */
@SpringBootTest
@Testcontainers
class FxRateServiceIT {

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static FakeRateProvider provider;

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) throws IOException {
        provider = new FakeRateProvider();
        registry.add("fx.provider.base-url", provider::baseUrl);
    }

    @AfterAll
    static void stopProvider() {
        provider.stop();
    }

    @Autowired
    private FxRateService fxRateService;
    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void reset() {
        Set<String> keys = redisTemplate.keys("fx:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        provider.reset();
    }

    @Test
    void concurrentMissesForOneBaseMakeExactlyOneProviderCall() throws Exception {
        // Slow enough that every caller arrives while the first fetch is still in flight.
        provider.slowDownTo(Duration.ofMillis(300));
        int callers = 10;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<FxQuote>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return fxRateService.quote("PLN", "EUR");
                }));
            }
            start.countDown();
            for (Future<FxQuote> result : results) {
                FxQuote quote = result.get(10, TimeUnit.SECONDS);
                assertThat(quote.rate()).isEqualByComparingTo("0.22819");
                assertThat(quote.stale()).isFalse();
            }
        }

        assertThat(provider.calls()).isEqualTo(1);
    }

    @Test
    void aSecondQuoteFromTheSameBaseIsServedFromRedis() {
        fxRateService.quote("EUR", "PLN");
        fxRateService.quote("EUR", "USD");

        assertThat(provider.calls()).isEqualTo(1);
        assertThat(redisTemplate.hasKey("fx:rates:EUR")).isTrue();
        assertThat(redisTemplate.hasKey("fx:rates:last-known:EUR")).isTrue();
    }

    @Test
    void aProviderOutageServesTheLastKnownRatesMarkedStale() {
        fxRateService.quote("GBP", "EUR");
        redisTemplate.delete("fx:rates:GBP"); // as if the fresh entry had expired
        provider.goDown();

        FxQuote quote = fxRateService.quote("GBP", "EUR");

        assertThat(quote.stale()).isTrue();
        assertThat(quote.asOf()).isEqualTo(LocalDate.of(2026, 9, 24));
    }
}
