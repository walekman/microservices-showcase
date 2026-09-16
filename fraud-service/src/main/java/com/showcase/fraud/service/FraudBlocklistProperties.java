package com.showcase.fraud.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.UUID;

@ConfigurationProperties(prefix = "fraud.blocklist")
public record FraudBlocklistProperties(List<UUID> accountIds) {

    // Boot's binder leaves this null when the property is unset entirely (no accounts
    // blocklisted) -- default to an empty list so FraudCheckService.check() never NPEs.
    public FraudBlocklistProperties {
        accountIds = (accountIds != null) ? List.copyOf(accountIds) : List.of();
    }
}
