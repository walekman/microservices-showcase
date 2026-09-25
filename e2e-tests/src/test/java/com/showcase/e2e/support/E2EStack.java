package com.showcase.e2e.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One copy of the whole Compose stack per JVM, started on first use and removed (with its
 * volumes) at JVM exit. Drives the docker compose CLI directly: Testcontainers' ComposeContainer
 * rejects any compose file that sets container_name, which docker-compose.yml does on every
 * service (see docs/phase-10-end-to-end-saga-tests.md, Design Decisions).
 *
 * <p>A run killed before the shutdown hook fires leaves its stack behind: `docker compose ls`
 * lists it as showcase-e2e-*, and `docker compose -p <name> down -v` removes it. Run with
 * -De2e.keepStack=true to keep the stack on purpose, e.g. to read a failed run's service logs.
 */
public final class E2EStack {

    private static final Path REPO_ROOT =
            Path.of(System.getProperty("e2e.repoRoot", "..")).toAbsolutePath().normalize();
    private static final Duration UP_TIMEOUT = Duration.ofMinutes(15);
    // The services docker-compose.yml builds from source. Built one at a time: six parallel
    // Maven dependency downloads and compiles exhausted Docker Desktop's build daemon (a DNS
    // failure mid-download on one run, the BuildKit connection dropping on the next).
    private static final List<String> BUILT_SERVICES = List.of(
            "account-service", "transfer-service", "notification-service",
            "fraud-service", "gateway-service", "fx-service");

    private static E2EStack instance;
    private static RuntimeException startFailure;

    private final String project = "showcase-e2e-" + UUID.randomUUID().toString().substring(0, 8);
    private URI gateway;
    private URI keycloak;
    private URI fraud;
    private URI transfer;
    private URI accountProxy;

    private E2EStack() {
    }

    public static synchronized E2EStack get() {
        if (startFailure != null) {
            throw startFailure;
        }
        if (instance == null) {
            E2EStack stack = new E2EStack();
            Runtime.getRuntime().addShutdownHook(new Thread(stack::down, "e2e-stack-down"));
            try {
                stack.up();
            } catch (RuntimeException ex) {
                startFailure = ex;
                throw ex;
            }
            instance = stack;
        }
        return instance;
    }

    public URI gateway() {
        return gateway;
    }

    public URI keycloak() {
        return keycloak;
    }

    public URI fraud() {
        return fraud;
    }

    public URI transfer() {
        return transfer;
    }

    public URI accountProxy() {
        return accountProxy;
    }

    private void up() {
        System.out.println("[e2e] starting stack " + project + " from " + REPO_ROOT);
        for (String service : BUILT_SERVICES) {
            compose(true, "build", service);
        }
        compose(true, "up", "--detach", "--wait", "--wait-timeout", String.valueOf(UP_TIMEOUT.toSeconds()));
        gateway = hostUrl("gateway-service", 8080);
        keycloak = hostUrl("keycloak", 8080);
        fraud = hostUrl("fraud-service", 8084);
        transfer = hostUrl("transfer-service", 8082);
        accountProxy = hostUrl("account-proxy", 8080);
        System.out.println("[e2e] stack " + project + " is up; gateway at " + gateway);
    }

    private void down() {
        if (Boolean.getBoolean("e2e.keepStack")) {
            System.out.println("[e2e] -De2e.keepStack=true: leaving " + project + " running. Inspect with `docker compose -p "
                    + project + " logs <service>`, remove with `docker compose -p " + project + " down -v`.");
            return;
        }
        try {
            compose(false, "down", "--volumes", "--remove-orphans");
        } catch (RuntimeException ex) {
            System.err.println("[e2e] could not remove stack " + project + "; remove it with `docker compose -p "
                    + project + " down -v`: " + ex.getMessage());
        }
    }

    /** `docker compose port` prints e.g. "0.0.0.0:55012"; only the port is kept. */
    private URI hostUrl(String service, int containerPort) {
        String mapped = compose(false, "port", service, String.valueOf(containerPort)).strip().lines()
                .findFirst().orElseThrow(() -> new IllegalStateException(service + ":" + containerPort + " is not published"));
        return URI.create("http://localhost:" + mapped.substring(mapped.lastIndexOf(':') + 1));
    }

    private String compose(boolean echo, String... args) {
        List<String> command = new ArrayList<>(List.of("docker", "compose",
                "--project-name", project,
                "--file", REPO_ROOT.resolve("docker-compose.yml").toString(),
                "--file", REPO_ROOT.resolve("docker-compose.e2e.yml").toString()));
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).directory(REPO_ROOT.toFile()).redirectErrorStream(true).start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                    if (echo) {
                        System.out.println("[e2e] " + line);
                    }
                }
            }
            if (process.waitFor() != 0) {
                throw new IllegalStateException("`" + String.join(" ", command) + "` failed:\n" + output);
            }
            return output.toString();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
