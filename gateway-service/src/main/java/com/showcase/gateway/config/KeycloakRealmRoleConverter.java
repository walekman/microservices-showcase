package com.showcase.gateway.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Keycloak embeds a user's effective realm roles (composite roles already expanded, e.g.
 * "customer" expands to "transfer-executor"/"account-reader"/"account-editor"/"fraud-checker")
 * under the nested "realm_access.roles" claim, not the "scope"/"scp" claim Spring Security's
 * default JwtGrantedAuthoritiesConverter reads. Authorities are the bare role names, no
 * "ROLE_" prefix, so hasAuthority(...) checks match exactly what Keycloak granted.
 */
public class KeycloakRealmRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null || !(realmAccess.get("roles") instanceof List<?> roles)) {
            return List.of();
        }
        return roles.stream()
                // A non-String element (a malformed realm_access.roles claim -- not
                // attacker-reachable, since the token is signed, but a protocol-mapper
                // misconfiguration could produce one) is skipped rather than thrown on --
                // the resulting reduced authority set still correctly denies whatever
                // permission-gated endpoint the caller was trying to reach, instead of a
                // ClassCastException surfacing as a 500. Found in code review.
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .<GrantedAuthority>map(SimpleGrantedAuthority::new)
                .toList();
    }
}
