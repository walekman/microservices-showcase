package com.showcase.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "account-service")
public record AccountServiceProperties(String baseUrl) {
}
