package com.showcase.fx.service;

import com.showcase.fx.support.FakeRateProvider;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;

/**
 * Redis is an optimisation, never a dependency: with nothing listening on the configured Redis
 * port, quotes still come from the provider, promptly (the 500ms Redis timeouts in
 * application.yml, not Lettuce's 60s default).
 */
@SpringBootTest(properties = {"spring.data.redis.host=localhost", "spring.data.redis.port=1"})
class FxRateServiceRedisDownIT {

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
    private MeterRegistry meterRegistry;

    @Test
    void withRedisUnreachableQuotesStillComeFromTheProviderPromptly() {
        FxQuote quote = assertTimeout(Duration.ofSeconds(5), () -> fxRateService.quote("PLN", "EUR"));

        assertThat(quote.rate()).isEqualByComparingTo("0.22819");
        assertThat(quote.stale()).isFalse();
        assertThat(meterRegistry.counter("fx.cache.requests", "result", "bypass").count()).isEqualTo(1);
    }
}
