package com.showcase.e2e.support;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;

/**
 * Faults on the Transfer -> Account link, through WireMock's admin API on account-proxy. Every
 * fault is a priority-1 stub over the file-backed priority-10 "forward everything" mapping, and
 * reset() drops back to that mapping alone.
 */
public final class AccountProxy {

    private static final String ACCOUNT_SERVICE = "http://account-service:8081";

    private final URI admin;

    public AccountProxy(URI admin) {
        this.admin = admin;
    }

    /** Every call from Transfer to Account fails as a reset connection: an I/O error, as for a crashed peer. */
    public void unreachable() {
        stub(Map.of(
                "priority", 1,
                "request", Map.of("urlPattern", ".*"),
                "response", Map.of("fault", "CONNECTION_RESET_BY_PEER")));
    }

    /**
     * Debits reach Account and commit, but their responses are held back for {@code delay}.
     * Longer than Transfer's 5 s read timeout, this is the case the saga invariant is about: the
     * money moved and Transfer cannot tell. Every other call is forwarded normally, so
     * pre-validation still succeeds.
     */
    public void delayDebitResponses(Duration delay) {
        stub(Map.of(
                "priority", 1,
                "request", Map.of("method", "POST", "urlPathPattern", "/accounts/[^/]+/debit"),
                "response", Map.of("proxyBaseUrl", ACCOUNT_SERVICE, "fixedDelayMilliseconds", delay.toMillis())));
    }

    public void reset() {
        Http.send(HttpRequest.newBuilder(admin.resolve("/__admin/mappings/reset"))
                        .POST(HttpRequest.BodyPublishers.noBody()))
                .expect(200);
    }

    private void stub(Map<String, Object> mapping) {
        Http.send(HttpRequest.newBuilder(admin.resolve("/__admin/mappings"))
                        .header("Content-Type", "application/json")
                        .POST(Http.json(mapping)))
                .expect(201);
    }
}
