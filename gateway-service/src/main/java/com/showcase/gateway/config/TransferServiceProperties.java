package com.showcase.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "transfer-service")
public record TransferServiceProperties(String baseUrl) {
}
