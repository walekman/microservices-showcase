package com.showcase.account.adapter.in.web;

import com.showcase.account.domain.Account;

public record AccountSummaryResponse(String ownerName, String currency) {

    public static AccountSummaryResponse from(Account account) {
        return new AccountSummaryResponse(account.getOwnerName(), account.getCurrency().name());
    }
}
