package com.showcase.account.application.port.in;

import com.showcase.account.domain.Account;
import com.showcase.account.domain.SupportedCurrency;

import java.util.List;
import java.util.UUID;

public interface AccountQueries {

    // Owner-gated: a non-owning caller gets the same AccountNotFoundException a genuinely
    // missing account would -- see docs/phase-7b-account-ownership-authorization.md's Design
    // Decisions for why this reuses 404/ACCOUNT_NOT_FOUND rather than a new 403 code.
    Account getAccount(UUID id, UUID callerId);

    // Existence and currency, no ownership -- backs GET /accounts/exists/{id}, which Transfer's
    // pre-validate step calls for both legs of a transfer (including the destination account,
    // which the initiating caller never owns), and which tells Transfer what currency each leg is
    // in. See docs/phase-7b-account-ownership-authorization.md and docs/phase-12-fx-rates-redis-cache.md.
    SupportedCurrency getCurrency(UUID id);

    // Admin-only at the web adapter/SecurityConfig layer (account-admin) -- deliberately
    // unfiltered here, same as before Phase 7b.
    List<Account> getAllAccounts();

    // "Mine" for the caller -- filtered by the same ownerId every other owner-gated method
    // reads from the JWT. Unlike getAllAccounts, this is safe for any authenticated caller:
    // each caller only ever sees their own list.
    List<Account> getMyAccounts(UUID ownerId);

    // No ownership check -- see AccountController.getAccountSummary's comment.
    Account getAccountSummary(UUID id);
}
