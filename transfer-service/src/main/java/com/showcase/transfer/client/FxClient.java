package com.showcase.transfer.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Asks FX Service for one rate. Unlike AccountClient/FraudClient there is only ONE outcome
 * exception: FX Service has no business rejections. Its 400 (an unsupported currency) can only
 * mean Account and FX disagree on the currency list -- a misconfiguration, not an answer about
 * this transfer -- so every non-2xx is "no rate available", same as a timeout. Called only from
 * the live saga, before anything is persisted; CompensationScheduler never calls it (see
 * docs/phase-12-fx-rates-redis-cache.md).
 *
 * <p>Same Resilience4j caution as FraudClient: with a fallbackMethod, every exception the body
 * throws -- and an open circuit's CallNotPermittedException -- goes through rateFallback.
 */
public class FxClient {

    private final RestClient restClient;

    public FxClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @CircuitBreaker(name = "fxService")
    @Retry(name = "fxService", fallbackMethod = "rateFallback")
    public FxRate rate(String base, String quote) {
        FxRate rate;
        try {
            rate = restClient.get()
                    .uri("/fx/rates?base={base}&quote={quote}", base, quote)
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (request, response) -> {
                        throw new FxServiceUnavailableException("FX Service returned " + response.getStatusCode().value());
                    })
                    .body(FxRate.class);
        } catch (RestClientException ex) {
            throw new FxServiceUnavailableException("FX Service call failed: " + ex.getMessage(), ex);
        }
        if (rate == null || rate.rate() == null || rate.rate().signum() <= 0) {
            throw new FxServiceUnavailableException("FX Service returned no usable rate for " + base + "->" + quote);
        }
        return rate;
    }

    private FxRate rateFallback(String base, String quote, Throwable t) {
        if (t instanceof FxServiceUnavailableException already) {
            throw already;
        }
        throw new FxServiceUnavailableException("FX Service call failed: " + t.getMessage(), t);
    }
}
