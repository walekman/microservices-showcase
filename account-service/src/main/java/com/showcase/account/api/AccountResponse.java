package com.showcase.account.api;

import com.showcase.account.domain.Account;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// Deliberately no ownerId field: POST /accounts/{id}/credit returns this same response and has
// no ownership check (see AccountService.credit's javadoc) -- exposing ownerId here would let
// any customer learn another account's owner by crediting it, exactly the identity leak
// docs/phase-7b-account-ownership-authorization.md's GET /accounts/exists/{id} split was meant
// to prevent. Found in code review.
public record AccountResponse(
        UUID id,
        String ownerName,
        BigDecimal balance,
        Instant createdAt) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(account.getId(), account.getOwnerName(), account.getBalance(), account.getCreatedAt());
    }
}
