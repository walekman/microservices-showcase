package com.showcase.fraud.service;

import com.showcase.fraud.domain.AccountBlockedException;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class FraudCheckService {

    private final FraudBlocklistProperties properties;

    public FraudCheckService(FraudBlocklistProperties properties) {
        this.properties = properties;
    }

    public void check(UUID accountId) {
        if (properties.accountIds().contains(accountId)) {
            throw new AccountBlockedException(accountId);
        }
    }
}
