package com.showcase.fraud.service;

import com.showcase.fraud.domain.AccountBlockedException;
import com.showcase.fraud.domain.BlockedAccountRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class FraudCheckService {

    private final BlockedAccountRepository repository;

    public FraudCheckService(BlockedAccountRepository repository) {
        this.repository = repository;
    }

    /**
     * A database failure propagates as a 500. Transfer's FraudClient reads any 5xx as "Fraud
     * unavailable", so an unreadable blocklist fails the screen closed, never open.
     */
    public void check(UUID accountId) {
        if (repository.existsById(accountId)) {
            throw new AccountBlockedException(accountId);
        }
    }
}
