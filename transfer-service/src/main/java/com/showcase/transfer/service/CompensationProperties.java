package com.showcase.transfer.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "transfer.compensation")
public record CompensationProperties(Duration sweepInterval, Duration pendingStaleAfter) {

    // See AccountClientProperties for why these are defaulted here rather than trusted to
    // always be set: Boot's binder skips a null Duration silently.
    public CompensationProperties {
        sweepInterval = (sweepInterval != null) ? sweepInterval : Duration.ofSeconds(15);
        pendingStaleAfter = (pendingStaleAfter != null) ? pendingStaleAfter : Duration.ofSeconds(120);
    }
}
