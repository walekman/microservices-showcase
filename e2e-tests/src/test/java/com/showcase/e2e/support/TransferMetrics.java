package com.showcase.e2e.support;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads Transfer Service's circuit-breaker state from its Prometheus scrape (unauthenticated). */
public final class TransferMetrics {

    private static final Pattern STATE = Pattern.compile("state=\"([a-z_]+)\"");

    private final URI transfer;

    public TransferMetrics(URI transfer) {
        this.transfer = transfer;
    }

    /**
     * Resilience4j exports one resilience4j_circuitbreaker_state sample per state and sets the
     * current one to 1. Returns that state ("closed", "open", "half_open", ...), or "absent"
     * before the breaker's first call has registered it.
     */
    public String circuitBreakerState(String name) {
        String scrape = Http.send(HttpRequest.newBuilder(transfer.resolve("/actuator/prometheus")).GET())
                .expect(200).raw();
        return scrape.lines()
                .filter(line -> line.startsWith("resilience4j_circuitbreaker_state{"))
                .filter(line -> line.contains("name=\"" + name + "\""))
                .filter(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)) == 1.0)
                .map(STATE::matcher)
                .filter(Matcher::find)
                .map(matcher -> matcher.group(1))
                .findFirst()
                .orElse("absent");
    }
}
