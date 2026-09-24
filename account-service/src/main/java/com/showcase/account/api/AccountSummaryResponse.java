package com.showcase.account.api;

import com.showcase.account.domain.Account;

public record AccountSummaryResponse(String ownerName, String currency) {

    public static AccountSummaryResponse from(Account account) {
        return new AccountSummaryResponse(account.getOwnerName(), account.getCurrency().name());
    }
}
