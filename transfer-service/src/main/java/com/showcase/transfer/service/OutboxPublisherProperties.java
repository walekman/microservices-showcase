package com.showcase.transfer.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "transfer.outbox")
public record OutboxPublisherProperties(Duration pollInterval, Integer batchSize, Duration publishTimeout,
                                         Topics topics) {

    public OutboxPublisherProperties {
        pollInterval = (pollInterval != null) ? pollInterval : Duration.ofSeconds(5);
        batchSize = (batchSize != null) ? batchSize : 500;
        publishTimeout = (publishTimeout != null) ? publishTimeout : Duration.ofSeconds(5);
        topics = (topics != null) ? topics : new Topics("transfer.completed", "transfer.failed");
    }

    public record Topics(String completed, String failed) {
    }
}
