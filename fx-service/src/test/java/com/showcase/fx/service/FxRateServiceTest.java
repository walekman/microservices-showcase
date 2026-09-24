package com.showcase.fx.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FxRateServiceTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 23);
    private static final RateTable PLN_RATES = new RateTable("PLN", AS_OF, Map.of(
            "EUR", new BigDecimal("0.22819"),
            "USD", new BigDecimal("0.25938"),
            "GBP", new BigDecimal("0.19621")));

    @Mock
    private RateCache cache;
    @Mock
    private RateProvider provider;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private FxRateService service;

    @BeforeEach
    void setUp() {
        // lockWait is short so the lock-loser tests don't slow the suite down.
        FxProperties properties = new FxProperties(List.of("EUR", "USD", "GBP", "PLN"),
                Duration.ofMinutes(10), Duration.ofHours(24), Duration.ofSeconds(5), Duration.ofMillis(200), null);
        service = new FxRateService(cache, provider, properties, registry);
    }

    private double cacheRequests(String result) {
        return registry.counter("fx.cache.requests", "result", result).count();
    }

    private double providerCalls(String outcome) {
        return registry.counter("fx.provider.calls", "outcome", outcome).count();
    }

    @Test
    void theSameCurrencyIsRateOneWithoutTouchingTheCacheOrTheProvider() {
        FxQuote quote = service.quote("EUR", "EUR");

        assertThat(quote.rate()).isEqualByComparingTo("1");
        assertThat(quote.stale()).isFalse();
        verifyNoInteractions(cache, provider);
    }

    @Test
    void currencyCodesAreNormalisedNotRejected() {
        when(cache.getFresh("PLN")).thenReturn(Optional.of(PLN_RATES));

        FxQuote quote = service.quote(" pln ", "eur");

        assertThat(quote.base()).isEqualTo("PLN");
        assertThat(quote.quote()).isEqualTo("EUR");
        assertThat(quote.rate()).isEqualByComparingTo("0.22819");
    }

    @Test
    void anUnsupportedOrMissingCurrencyIsRejectedBeforeAnyLookup() {
        assertThatThrownBy(() -> service.quote("JPY", "EUR")).isInstanceOf(UnsupportedCurrencyException.class);
        assertThatThrownBy(() -> service.quote("PLN", null)).isInstanceOf(UnsupportedCurrencyException.class);
        verifyNoInteractions(cache, provider);
    }

    @Test
    void aFreshHitIsServedWithoutTheProvider() {
        when(cache.getFresh("PLN")).thenReturn(Optional.of(PLN_RATES));

        FxQuote quote = service.quote("PLN", "EUR");

        assertThat(quote).isEqualTo(new FxQuote("PLN", "EUR", new BigDecimal("0.22819"), AS_OF, false));
        verifyNoInteractions(provider);
        verify(cache, never()).tryLock(any());
        assertThat(cacheRequests("hit")).isEqualTo(1);
    }

    @Test
    void aMissFetchesThenCachesThenReleasesTheLock() {
        when(cache.getFresh("PLN")).thenReturn(Optional.empty());
        when(cache.tryLock("PLN")).thenReturn(Optional.of("token-1"));
        when(provider.fetch("PLN")).thenReturn(PLN_RATES);

        FxQuote quote = service.quote("PLN", "USD");

        assertThat(quote.rate()).isEqualByComparingTo("0.25938");
        assertThat(quote.stale()).isFalse();
        InOrder inOrder = inOrder(provider, cache);
        inOrder.verify(provider).fetch("PLN");
        inOrder.verify(cache).put(PLN_RATES);
        inOrder.verify(cache).unlock("PLN", "token-1");
        assertThat(cacheRequests("miss")).isEqualTo(1);
        assertThat(providerCalls("success")).isEqualTo(1);
    }

    @Test
    void aProviderFailureServesTheLastKnownRatesMarkedStale() {
        when(cache.getFresh("PLN")).thenReturn(Optional.empty());
        when(cache.tryLock("PLN")).thenReturn(Optional.of("token-1"));
        when(provider.fetch("PLN")).thenThrow(new ProviderUnavailableException("provider down"));
        when(cache.getLastKnown("PLN")).thenReturn(Optional.of(PLN_RATES));

        FxQuote quote = service.quote("PLN", "EUR");

        assertThat(quote.stale()).isTrue();
        assertThat(quote.asOf()).isEqualTo(AS_OF);
        verify(cache, never()).put(any());
        verify(cache).unlock("PLN", "token-1");
        assertThat(cacheRequests("stale")).isEqualTo(1);
        assertThat(providerCalls("failure")).isEqualTo(1);
    }

    @Test
    void aProviderFailureWithNothingCachedIsUnavailable() {
        when(cache.getFresh("PLN")).thenReturn(Optional.empty());
        when(cache.tryLock("PLN")).thenReturn(Optional.of("token-1"));
        when(provider.fetch("PLN")).thenThrow(new ProviderUnavailableException("provider down"));
        when(cache.getLastKnown("PLN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.quote("PLN", "EUR")).isInstanceOf(RateUnavailableException.class);
        verify(cache).unlock("PLN", "token-1");
    }

    @Test
    void losingTheLockWaitsForTheWinnersRatesInsteadOfCallingTheProvider() {
        when(cache.getFresh("PLN")).thenReturn(Optional.empty(), Optional.empty(), Optional.of(PLN_RATES));
        when(cache.tryLock("PLN")).thenReturn(Optional.empty());

        FxQuote quote = service.quote("PLN", "EUR");

        assertThat(quote.stale()).isFalse();
        verifyNoInteractions(provider);
        verify(cache, never()).unlock(any(), any());
    }

    @Test
    void losingTheLockAndTimingOutFallsBackToTheLastKnownRates() {
        when(cache.getFresh("PLN")).thenReturn(Optional.empty());
        when(cache.tryLock("PLN")).thenReturn(Optional.empty());
        when(cache.getLastKnown("PLN")).thenReturn(Optional.of(PLN_RATES));

        FxQuote quote = service.quote("PLN", "EUR");

        assertThat(quote.stale()).isTrue();
        verifyNoInteractions(provider);
    }

    @Test
    void redisDownBypassesTheCacheAndAsksTheProvider() {
        when(cache.getFresh("PLN")).thenThrow(new RedisConnectionFailureException("connection refused"));
        when(provider.fetch("PLN")).thenReturn(PLN_RATES);

        FxQuote quote = service.quote("PLN", "EUR");

        assertThat(quote.rate()).isEqualByComparingTo("0.22819");
        assertThat(quote.stale()).isFalse();
        assertThat(cacheRequests("bypass")).isEqualTo(1);
    }

    @Test
    void redisDownAndTheProviderDownIsUnavailable() {
        when(cache.getFresh("PLN")).thenThrow(new RedisConnectionFailureException("connection refused"));
        when(provider.fetch("PLN")).thenThrow(new ProviderUnavailableException("provider down"));

        assertThatThrownBy(() -> service.quote("PLN", "EUR")).isInstanceOf(RateUnavailableException.class);
    }

    @Test
    void aFailedCacheWriteStillServesTheRatesAlreadyFetched() {
        when(cache.getFresh("PLN")).thenReturn(Optional.empty());
        when(cache.tryLock("PLN")).thenReturn(Optional.of("token-1"));
        when(provider.fetch("PLN")).thenReturn(PLN_RATES);
        doThrow(new RedisConnectionFailureException("gone")).when(cache).put(PLN_RATES);

        FxQuote quote = service.quote("PLN", "EUR");

        assertThat(quote.rate()).isEqualByComparingTo("0.22819");
        verify(provider, times(1)).fetch("PLN");
    }

    @Test
    void aQuoteMissingFromTheProvidersDataIsUnavailableNotAnNpe() {
        RateTable partial = new RateTable("PLN", AS_OF, Map.of("EUR", new BigDecimal("0.22819")));
        when(cache.getFresh("PLN")).thenReturn(Optional.of(partial));

        assertThatThrownBy(() -> service.quote("PLN", "GBP")).isInstanceOf(RateUnavailableException.class);
    }
}
