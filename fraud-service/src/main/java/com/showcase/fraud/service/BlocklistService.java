package com.showcase.fraud.service;

import com.showcase.fraud.domain.BlockedAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Adds and removes blocklist entries. Both operations are idempotent. Neither checks that the
 * account exists: Fraud never calls Account, and a block placed before an account is created
 * still applies once it is.
 */
@Service
public class BlocklistService {

    private static final Logger log = LoggerFactory.getLogger(BlocklistService.class);

    private final BlockedAccountRepository repository;

    public BlocklistService(BlockedAccountRepository repository) {
        this.repository = repository;
    }

    public void block(UUID accountId) {
        if (repository.blockIfAbsent(accountId, Instant.now()) == 1) {
            log.info("Account {} added to the blocklist", accountId);
        }
    }

    public void unblock(UUID accountId) {
        if (repository.unblock(accountId) == 1) {
            log.info("Account {} removed from the blocklist", accountId);
        }
    }
}
