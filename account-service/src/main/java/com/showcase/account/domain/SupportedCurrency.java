package com.showcase.account.domain;

/**
 * The currencies an account can be held in. Must list the same codes as fx-service's
 * fx.supported-currencies: a currency Account accepts but FX does not would fail every transfer
 * out of it. See docs/phase-12-fx-rates-redis-cache.md.
 */
public enum SupportedCurrency {
    EUR, USD, GBP, PLN
}
