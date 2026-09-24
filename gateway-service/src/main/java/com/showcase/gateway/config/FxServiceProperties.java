package com.showcase.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fx-service")
public record FxServiceProperties(String baseUrl) {
}
