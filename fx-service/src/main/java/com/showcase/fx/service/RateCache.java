package com.showcase.fx.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The Redis side of FX Service's cache-aside. Three keys per base currency (see
 * docs/phase-12-fx-rates-redis-cache.md, "Cache"): the fresh rates, a longer-lived last-known copy,
 * and a short lock that lets exactly one caller -- across every instance -- fetch from the
 * provider on a miss.
 *
 * <p>Redis failures are NOT caught here: they surface as Spring's DataAccessException, and
 * FxRateService decides what a dead Redis means (go to the provider directly).
 */
@Component
public class RateCache {

    static final String FRESH_PREFIX = "fx:rates:";
    static final String LAST_KNOWN_PREFIX = "fx:rates:last-known:";
    static final String LOCK_PREFIX = "fx:lock:";

    private static final Logger log = LoggerFactory.getLogger(RateCache.class);

    // Compare-and-delete in one atomic step. A plain DEL could remove a lock that already expired
    // and was taken by another caller -- whose fetch would then be unprotected.
    private static final RedisScript<Long> RELEASE_LOCK = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final FxProperties properties;

    public RateCache(StringRedisTemplate redis, ObjectMapper objectMapper, FxProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public Optional<RateTable> getFresh(String base) {
        return read(FRESH_PREFIX + base);
    }

    public Optional<RateTable> getLastKnown(String base) {
        return read(LAST_KNOWN_PREFIX + base);
    }

    public void put(RateTable table) {
        String json = write(table);
        redis.opsForValue().set(FRESH_PREFIX + table.base(), json, properties.freshTtl());
        redis.opsForValue().set(LAST_KNOWN_PREFIX + table.base(), json, properties.lastKnownTtl());
    }

    /** The token to release the lock with, or empty if another caller already holds it. */
    public Optional<String> tryLock(String base) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent(LOCK_PREFIX + base, token, properties.lockTtl());
        return Boolean.TRUE.equals(acquired) ? Optional.of(token) : Optional.empty();
    }

    public void unlock(String base, String token) {
        redis.execute(RELEASE_LOCK, List.of(LOCK_PREFIX + base), token);
    }

    private Optional<RateTable> read(String key) {
        String json = redis.opsForValue().get(key);
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, RateTable.class));
        } catch (JsonProcessingException unreadable) {
            // Treated as absent rather than as an error: the next successful fetch overwrites it.
            log.warn("Ignoring unreadable FX cache entry {}: {}", key, unreadable.getMessage());
            return Optional.empty();
        }
    }

    private String write(RateTable table) {
        try {
            return objectMapper.writeValueAsString(table);
        } catch (JsonProcessingException impossible) {
            // A String, a LocalDate and a Map<String, BigDecimal> -- Boot's ObjectMapper
            // (JavaTimeModule registered) cannot fail on this.
            throw new IllegalStateException("Failed to serialize rates for " + table.base(), impossible);
        }
    }
}
