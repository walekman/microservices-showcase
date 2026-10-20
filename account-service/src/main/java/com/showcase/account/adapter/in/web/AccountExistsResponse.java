package com.showcase.account.adapter.in.web;

/** GET /accounts/exists/{id}: the account exists, and this is the currency it is held in. Nothing else. */
public record AccountExistsResponse(String currency) {
}
