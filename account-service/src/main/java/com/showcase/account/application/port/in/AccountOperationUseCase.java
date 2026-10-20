package com.showcase.account.application.port.in;

import com.showcase.account.domain.Account;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Debit and credit, both idempotent per idempotency key: a repeat of an already-applied key
 * returns the account without applying it again.
 */
public interface AccountOperationUseCase {

    // Owner-gated, with an exemption for Transfer Service's own machine identity
    // (serviceCaller=true), needed so CompensationScheduler's stale-PENDING reconciliation can
    // replay a debit it doesn't hold the original customer's token for. See
    // docs/phase-7b-account-ownership-authorization.md's Design Decisions.
    Account debit(UUID id, BigDecimal amount, String currency, String idempotencyKey,
                  UUID callerId, boolean serviceCaller);

    // No ownership check -- cannot be enforced here. A real transfer's credit call always
    // carries the SOURCE customer's token, never the destination owner's, so "caller owns this
    // account" would reject every legitimate transfer to someone else. See
    // docs/phase-7b-account-ownership-authorization.md's Design Decisions for the still-open gap
    // this leaves (a customer can mint money via a direct credit call).
    Account credit(UUID id, BigDecimal amount, String currency, String idempotencyKey);
}
