package com.showcase.e2e.support;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fresh Keycloak users, one per role in a test. Fresh because Account allows one account per
 * owner (Phase 9), and because it isolates every test's balances. New users get the realm's
 * default roles, which include the customer composite. Tokens come from a password grant
 * through showcase-ui, which allows direct grants.
 */
public final class TestUsers {

    private static final String PASSWORD = "e2e-password";

    private final URI keycloak;

    public TestUsers(URI keycloak) {
        this.keycloak = keycloak;
    }

    public TestUser create() {
        String username = "e2e-" + UUID.randomUUID().toString().substring(0, 8);
        Http.send(HttpRequest.newBuilder(keycloak.resolve("/admin/realms/showcase/users"))
                        .header("Authorization", "Bearer " + masterAdminToken())
                        .header("Content-Type", "application/json")
                        .POST(Http.json(Map.of(
                                "username", username,
                                "enabled", true,
                                // Keycloak 26's user profile requires these before a password grant
                                // will issue a token ("Account is not fully set up").
                                "email", username + "@e2e.example",
                                "emailVerified", true,
                                "firstName", "E2E",
                                "lastName", username,
                                "credentials", List.of(Map.of("type", "password", "value", PASSWORD, "temporary", false))))))
                .expect(201);
        return new TestUser(username, token("showcase", "showcase-ui", username, PASSWORD));
    }

    /** The realm's `admin` demo user: account-admin, transfer-admin and fraud-admin. */
    public String showcaseAdminToken() {
        return token("showcase", "showcase-ui", "admin", "password");
    }

    /** Keycloak's own administrator (KEYCLOAK_ADMIN in docker-compose.yml), for the admin REST API. */
    private String masterAdminToken() {
        return token("master", "admin-cli", "admin", "admin");
    }

    private String token(String realm, String clientId, String username, String password) {
        return Http.send(HttpRequest.newBuilder(keycloak.resolve("/realms/" + realm + "/protocol/openid-connect/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(Http.form(Map.of(
                                "grant_type", "password",
                                "client_id", clientId,
                                "username", username,
                                "password", password))))
                .expect(200)
                .text("access_token");
    }
}
