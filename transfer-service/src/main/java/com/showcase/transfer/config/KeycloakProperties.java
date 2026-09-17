package com.showcase.transfer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "keycloak")
public record KeycloakProperties(String issuerUri, String jwkSetUri) {
}
