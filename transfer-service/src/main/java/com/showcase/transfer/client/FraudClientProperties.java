package com.showcase.transfer.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "fraud-service")
public record FraudClientProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {

    public FraudClientProperties {
        connectTimeout = (connectTimeout != null) ? connectTimeout : Duration.ofSeconds(2);
        readTimeout = (readTimeout != null) ? readTimeout : Duration.ofSeconds(5);
    }
}
