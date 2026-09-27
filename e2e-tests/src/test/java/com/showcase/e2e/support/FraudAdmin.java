package com.showcase.e2e.support;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** The operator's blocklist API, called on Fraud Service directly (it has no Gateway route). */
public final class FraudAdmin {

    private final URI fraud;
    private final TestUsers users;
    private final Set<UUID> blocked = new HashSet<>();

    public FraudAdmin(URI fraud, TestUsers users) {
        this.fraud = fraud;
        this.users = users;
    }

    public void block(OpenedAccount account) {
        send(account.id(), "PUT");
        blocked.add(account.id());
    }

    /** Lifts every block this instance placed, so no test leaves a blocked account behind. */
    public void unblockAll() {
        for (UUID accountId : blocked) {
            send(accountId, "DELETE");
        }
        blocked.clear();
    }

    private void send(UUID accountId, String method) {
        Http.send(HttpRequest.newBuilder(fraud.resolve("/fraud/blocklist/" + accountId))
                        .header("Authorization", "Bearer " + users.showcaseAdminToken())
                        .method(method, HttpRequest.BodyPublishers.noBody()))
                .expect(204);
    }
}
