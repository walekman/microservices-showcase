package com.showcase.platform.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakRealmRoleConverterTest {

    private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

    @Test
    void mapsRealmRolesToBareAuthorities() {
        assertThat(authorities(jwt(Map.of("roles", List.of("account-reader", "fx-reader")))))
                .containsExactly("account-reader", "fx-reader");
    }

    @Test
    void noRealmAccessClaimMeansNoAuthorities() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("s").build();
        assertThat(converter.convert(jwt)).isEmpty();
    }

    @Test
    void skipsNonStringRoles() {
        assertThat(authorities(jwt(Map.of("roles", Arrays.asList("account-reader", 42)))))
                .containsExactly("account-reader");
    }

    private List<String> authorities(Jwt jwt) {
        return converter.convert(jwt).stream().map(GrantedAuthority::getAuthority).toList();
    }

    private static Jwt jwt(Map<String, Object> realmAccess) {
        return Jwt.withTokenValue("t").header("alg", "none").subject("s").claim("realm_access", realmAccess).build();
    }
}
