package com.showcase.account.domain;

import java.util.UUID;

/**
 * A debit or credit named a currency the account is not held in. Never a legitimate request:
 * Transfer computes every amount for the account's own currency, so this turns a pricing bug into
 * a clean rejection instead of silently wrong money. See docs/phase-12-fx-rates-redis-cache.md.
 */
public class CurrencyMismatchException extends RuntimeException {

    public CurrencyMismatchException(UUID accountId, SupportedCurrency accountCurrency, String requestedCurrency) {
        super("Account %s is held in %s, not %s".formatted(accountId, accountCurrency, requestedCurrency));
    }
}
