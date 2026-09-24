package com.showcase.fx.support;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stand-in for api.frankfurter.dev, in the repo's usual JDK-HttpServer style (see e.g.
 * transfer-service's FraudClientFallbackIT). Counts calls and can be slowed down or switched off.
 */
public final class FakeRateProvider {

    private final HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicBoolean down = new AtomicBoolean();
    private volatile Duration delay = Duration.ZERO;

    public FakeRateProvider() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/latest", exchange -> {
            calls.incrementAndGet();
            try {
                Thread.sleep(delay);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            if (down.get()) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            String base = exchange.getRequestURI().getQuery().replaceAll(".*base=([A-Z]{3}).*", "$1");
            byte[] body = """
                    {"amount":1.0,"base":"%s","date":"2026-09-24","rates":{"EUR":0.22819,"GBP":0.19621,"USD":0.25938,"PLN":4.38231}}
                    """.formatted(base).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
    }

    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    public int calls() {
        return calls.get();
    }

    public void reset() {
        calls.set(0);
        down.set(false);
        delay = Duration.ZERO;
    }

    public void goDown() {
        down.set(true);
    }

    public void slowDownTo(Duration delay) {
        this.delay = delay;
    }

    public void stop() {
        server.stop(0);
    }
}
