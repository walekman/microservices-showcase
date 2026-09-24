package com.showcase.fx.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// fresh-ttl shortened so expiry can be observed; last-known keeps its real 24h.
@SpringBootTest(properties = "fx.fresh-ttl=1s")
@Testcontainers
class RateCacheIT {

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static final RateTable PLN_RATES = new RateTable("PLN", LocalDate.of(2026, 9, 23),
            Map.of("EUR", new BigDecimal("0.22819"), "USD", new BigDecimal("0.25938")));

    @Autowired
    private RateCache cache;
    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void clearRedis() {
        Set<String> keys = redisTemplate.keys("fx:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    void putWritesTheFreshAndLastKnownKeysEachWithItsOwnTtl() {
        cache.put(PLN_RATES);

        assertThat(redisTemplate.getExpire("fx:rates:PLN", TimeUnit.MILLISECONDS)).isBetween(1L, 1_000L);
        assertThat(redisTemplate.getExpire("fx:rates:last-known:PLN", TimeUnit.SECONDS)).isBetween(86_000L, 86_400L);
    }

    @Test
    void aRateTableSurvivesTheRoundTripThroughRedis() {
        cache.put(PLN_RATES);

        assertThat(cache.getFresh("PLN")).contains(PLN_RATES);
        assertThat(cache.getLastKnown("PLN")).contains(PLN_RATES);
    }

    @Test
    void theFreshEntryExpiresWhileTheLastKnownOneSurvives() {
        cache.put(PLN_RATES);

        await().atMost(Duration.ofSeconds(5)).until(() -> cache.getFresh("PLN").isEmpty());
        assertThat(cache.getLastKnown("PLN")).contains(PLN_RATES);
    }

    @Test
    void onlyOneCallerHoldsTheLockUntilItIsReleased() {
        Optional<String> first = cache.tryLock("PLN");

        assertThat(first).isPresent();
        assertThat(cache.tryLock("PLN")).isEmpty();
        cache.unlock("PLN", first.get());
        assertThat(cache.tryLock("PLN")).isPresent();
    }

    @Test
    void releasingWithSomeoneElsesTokenLeavesTheLockInPlace() {
        String token = cache.tryLock("PLN").orElseThrow();

        cache.unlock("PLN", "not-" + token);

        assertThat(redisTemplate.opsForValue().get("fx:lock:PLN")).isEqualTo(token);
    }

    @Test
    void theLockExpiresOnItsOwnSoACrashedHolderCannotWedgeIt() {
        cache.tryLock("PLN").orElseThrow();

        assertThat(redisTemplate.getExpire("fx:lock:PLN", TimeUnit.MILLISECONDS)).isBetween(1L, 5_000L);
    }

    @Test
    void anUnreadableEntryReadsAsAbsent() {
        redisTemplate.opsForValue().set("fx:rates:PLN", "not json");

        assertThat(cache.getFresh("PLN")).isEmpty();
    }
}
