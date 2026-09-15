package com.showcase.transfer.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "transfer.compensation")
public record CompensationProperties(Duration sweepInterval, Duration pendingStaleAfter, Integer sweepBatchSize) {

    // See AccountClientProperties for why these are defaulted here rather than trusted to
    // always be set: Boot's binder skips a null Duration/Integer silently.
    public CompensationProperties {
        sweepInterval = (sweepInterval != null) ? sweepInterval : Duration.ofSeconds(15);
        pendingStaleAfter = (pendingStaleAfter != null) ? pendingStaleAfter : Duration.ofSeconds(120);
        // Fine at this project's scale unbounded, but each sweep previously fetched every
        // matching row with no limit at all -- bounded here so a future spike in transfer
        // volume cannot turn one sweep tick into an unbounded query and an unbounded batch
        // of outbound Account Service calls. See docs/roadmap.md's Phase 3 final-review
        // finding.
        sweepBatchSize = (sweepBatchSize != null) ? sweepBatchSize : 500;
    }
}
