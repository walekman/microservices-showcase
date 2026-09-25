package com.showcase.e2e.support;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Map;
import java.util.UUID;

/** A customer's view of the system: everything goes through the Gateway with the user's own token. */
public final class Bank {

    private final URI gateway;

    public Bank(URI gateway) {
        this.gateway = gateway;
    }

    public OpenedAccount openAccount(TestUser owner, String currency, String initialBalance) {
        HttpResult created = Http.send(as(owner, "/accounts")
                        .header("Content-Type", "application/json")
                        .POST(Http.json(Map.of(
                                "ownerName", owner.username(),
                                "initialBalance", new BigDecimal(initialBalance),
                                "currency", currency))))
                .expect(201);
        return new OpenedAccount(created.uuid("id"), owner);
    }

    /** Read by the account's owner, through the Gateway, which reaches Account directly (never the fault proxy). */
    public BigDecimal balance(OpenedAccount account) {
        return Http.send(as(account.owner(), "/accounts/" + account.id()).GET()).expect(200).decimal("balance");
    }

    public HttpResult transfer(TestUser initiator, OpenedAccount from, OpenedAccount to, String amount) {
        return transfer(initiator, from, to, amount, UUID.randomUUID().toString());
    }

    public HttpResult transfer(TestUser initiator, OpenedAccount from, OpenedAccount to, String amount,
                               String idempotencyKey) {
        return Http.send(as(initiator, "/transfers")
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .POST(Http.json(Map.of(
                        "fromAccountId", from.id(),
                        "toAccountId", to.id(),
                        "amount", new BigDecimal(amount)))));
    }

    public HttpResult getTransfer(TestUser user, UUID transferId) {
        return Http.send(as(user, "/transfers/" + transferId).GET()).expect(200);
    }

    private HttpRequest.Builder as(TestUser user, String path) {
        return HttpRequest.newBuilder(gateway.resolve(path)).header("Authorization", "Bearer " + user.accessToken());
    }
}
