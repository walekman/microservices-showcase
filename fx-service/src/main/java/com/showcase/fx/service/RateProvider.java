package com.showcase.fx.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Fetches ECB reference rates from Frankfurter (https://frankfurter.dev). One call per base
 * currency returns the rates to every other supported currency. Every failure -- a non-2xx, a
 * timeout, a body that is not what was asked for -- collapses onto ProviderUnavailableException,
 * so FxRateService only ever has to decide "fresh rates" or "fall back".
 */
public class RateProvider {

    private final RestClient restClient;
    private final List<String> supportedCurrencies;

    public RateProvider(RestClient restClient, List<String> supportedCurrencies) {
        this.restClient = restClient;
        this.supportedCurrencies = List.copyOf(supportedCurrencies);
    }

    public RateTable fetch(String base) {
        String symbols = supportedCurrencies.stream()
                .filter(currency -> !currency.equals(base))
                .collect(Collectors.joining(","));
        FrankfurterResponse body;
        try {
            body = restClient.get()
                    .uri("/latest?base={base}&symbols={symbols}", base, symbols)
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (request, response) -> {
                        throw new ProviderUnavailableException(
                                "FX provider returned " + response.getStatusCode().value());
                    })
                    .body(FrankfurterResponse.class);
        } catch (RestClientException ex) {
            throw new ProviderUnavailableException("FX provider call failed: " + ex.getMessage(), ex);
        }
        if (body == null || body.date() == null || body.rates() == null || !base.equals(body.base())
                || body.rates().containsValue(null)) {
            throw new ProviderUnavailableException("FX provider returned an unusable body for base " + base);
        }
        return new RateTable(base, body.date(), Map.copyOf(body.rates()));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FrankfurterResponse(String base, LocalDate date, Map<String, BigDecimal> rates) {
    }
}
