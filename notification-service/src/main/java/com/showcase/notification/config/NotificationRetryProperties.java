package com.showcase.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Backoff between delivery attempts of a record that failed transiently. No attempt limit. */
@ConfigurationProperties(prefix = "notification.retry")
public record NotificationRetryProperties(Duration initialInterval, double multiplier, Duration maxInterval) {
}
