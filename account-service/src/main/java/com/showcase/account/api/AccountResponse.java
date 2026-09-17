package com.showcase.account.api;

import com.showcase.account.domain.Account;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        UUID ownerId,
        String ownerName,
        BigDecimal balance,
        Instant createdAt) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(), account.getOwnerId(), account.getOwnerName(), account.getBalance(), account.getCreatedAt());
    }
}
