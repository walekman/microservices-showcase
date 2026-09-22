package com.showcase.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bank-ui")
public record BankUiProperties(String origin) {
}
