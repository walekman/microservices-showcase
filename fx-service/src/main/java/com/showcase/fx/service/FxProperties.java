package com.showcase.fx.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "fx")
public record FxProperties(List<String> supportedCurrencies, Duration freshTtl, Duration lastKnownTtl,
                           Duration lockTtl, Duration lockWait, Provider provider) {

    public FxProperties {
        supportedCurrencies = (supportedCurrencies != null) ? List.copyOf(supportedCurrencies) : List.of();
        freshTtl = (freshTtl != null) ? freshTtl : Duration.ofMinutes(10);
        lastKnownTtl = (lastKnownTtl != null) ? lastKnownTtl : Duration.ofHours(24);
        lockTtl = (lockTtl != null) ? lockTtl : Duration.ofSeconds(5);
        lockWait = (lockWait != null) ? lockWait : Duration.ofSeconds(1);
        provider = (provider != null) ? provider : new Provider(null, null, null);
    }

    public record Provider(String baseUrl, Duration connectTimeout, Duration readTimeout) {

        public Provider {
            baseUrl = (baseUrl != null) ? baseUrl : "https://api.frankfurter.dev/v1";
            connectTimeout = (connectTimeout != null) ? connectTimeout : Duration.ofSeconds(2);
            readTimeout = (readTimeout != null) ? readTimeout : Duration.ofSeconds(3);
        }
    }
}
