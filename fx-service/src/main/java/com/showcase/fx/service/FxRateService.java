package com.showcase.fx.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;

/**
 * Answers GET /fx/rates through the Redis cache in front of the rate provider. The read path and
 * how it degrades are specified in docs/phase-12-fx-rates-redis-cache.md ("Cache"): fresh hit;
 * else one caller fetches under a Redis lock while the others wait for its result; a provider
 * failure falls back to the last known rates, marked stale; a dead Redis is bypassed entirely.
 */
@Service
public class FxRateService {

    private static final Logger log = LoggerFactory.getLogger(FxRateService.class);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

    private final RateCache cache;
    private final RateProvider provider;
    private final FxProperties properties;
    private final Counter hits;
    private final Counter misses;
    private final Counter staleServes;
    private final Counter bypasses;
    private final Counter providerSuccesses;
    private final Counter providerFailures;

    public FxRateService(RateCache cache, RateProvider provider, FxProperties properties, MeterRegistry registry) {
        this.cache = cache;
        this.provider = provider;
        this.properties = properties;
        this.hits = cacheRequests(registry, "hit");
        this.misses = cacheRequests(registry, "miss");
        this.staleServes = cacheRequests(registry, "stale");
        this.bypasses = cacheRequests(registry, "bypass");
        this.providerSuccesses = providerCalls(registry, "success");
        this.providerFailures = providerCalls(registry, "failure");
    }

    private static Counter cacheRequests(MeterRegistry registry, String result) {
        return Counter.builder("fx.cache.requests").tag("result", result)
                .description("FX rate lookups by how the cache answered them").register(registry);
    }

    private static Counter providerCalls(MeterRegistry registry, String outcome) {
        return Counter.builder("fx.provider.calls").tag("outcome", outcome)
                .description("Calls to the external FX rate provider").register(registry);
    }

    public FxQuote quote(String base, String quote) {
        String from = requireSupported(base);
        String to = requireSupported(quote);
        if (from.equals(to)) {
            return new FxQuote(from, to, BigDecimal.ONE, LocalDate.now(ZoneOffset.UTC), false);
        }
        Lookup lookup = ratesFor(from);
        BigDecimal rate = lookup.table().rates().get(to);
        if (rate == null) {
            throw new RateUnavailableException("The provider's " + from + " rates have no " + to + " rate");
        }
        return new FxQuote(from, to, rate, lookup.table().asOf(), lookup.stale());
    }

    private String requireSupported(String currency) {
        String normalised = (currency == null) ? "" : currency.trim().toUpperCase(Locale.ROOT);
        if (!properties.supportedCurrencies().contains(normalised)) {
            throw new UnsupportedCurrencyException(currency);
        }
        return normalised;
    }

    private Lookup ratesFor(String base) {
        try {
            return lookUpThroughCache(base);
        } catch (DataAccessException redisUnavailable) {
            bypasses.increment();
            log.warn("Redis unavailable, fetching {} rates straight from the provider: {}",
                    base, redisUnavailable.getMessage());
            try {
                return new Lookup(fetch(base), false);
            } catch (ProviderUnavailableException providerAlsoDown) {
                throw new RateUnavailableException(
                        "No " + base + " rates: Redis and the provider are both unavailable", providerAlsoDown);
            }
        }
    }

    private Lookup lookUpThroughCache(String base) {
        Optional<RateTable> fresh = cache.getFresh(base);
        if (fresh.isPresent()) {
            hits.increment();
            return new Lookup(fresh.get(), false);
        }
        misses.increment();

        Optional<String> lock = cache.tryLock(base);
        if (lock.isEmpty()) {
            // Another caller (possibly on another instance) is fetching right now. Wait for its
            // result rather than calling the provider a second time.
            return waitForFresh(base)
                    .map(table -> new Lookup(table, false))
                    .orElseGet(() -> lastKnown(base));
        }
        try {
            RateTable table = fetch(base);
            try {
                cache.put(table);
            } catch (DataAccessException cacheWriteFailed) {
                // The rates are in hand; failing to cache them only costs the next caller a fetch.
                log.warn("Could not cache {} rates: {}", base, cacheWriteFailed.getMessage());
            }
            return new Lookup(table, false);
        } catch (ProviderUnavailableException ex) {
            log.warn("FX provider unavailable for {}, falling back to the last known rates: {}", base, ex.getMessage());
            return lastKnown(base);
        } finally {
            release(base, lock.get());
        }
    }

    private Optional<RateTable> waitForFresh(String base) {
        long deadline = System.nanoTime() + properties.lockWait().toNanos();
        while (System.nanoTime() < deadline) {
            pause();
            Optional<RateTable> fresh = cache.getFresh(base);
            if (fresh.isPresent()) {
                return fresh;
            }
        }
        return Optional.empty();
    }

    private Lookup lastKnown(String base) {
        RateTable table = cache.getLastKnown(base).orElseThrow(() -> new RateUnavailableException(
                "No " + base + " rates: the provider is unavailable and nothing is cached"));
        staleServes.increment();
        return new Lookup(table, true);
    }

    private RateTable fetch(String base) {
        try {
            RateTable table = provider.fetch(base);
            providerSuccesses.increment();
            return table;
        } catch (ProviderUnavailableException ex) {
            providerFailures.increment();
            throw ex;
        }
    }

    private void release(String base, String token) {
        try {
            cache.unlock(base, token);
        } catch (DataAccessException ex) {
            // Harmless: the lock expires on its own after fx.lock-ttl.
            log.warn("Could not release the {} rate lock: {}", base, ex.getMessage());
        }
    }

    private static void pause() {
        try {
            Thread.sleep(POLL_INTERVAL);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RateUnavailableException("Interrupted while waiting for rates", ex);
        }
    }

    private record Lookup(RateTable table, boolean stale) {
    }
}
