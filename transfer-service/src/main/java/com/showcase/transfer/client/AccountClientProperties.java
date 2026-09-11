package com.showcase.transfer.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "account-service")
public record AccountClientProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {
}
