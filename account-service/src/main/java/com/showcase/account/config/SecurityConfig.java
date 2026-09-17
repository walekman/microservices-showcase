package com.showcase.account.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Independently validates every request's JWT -- never trusts that the Gateway already
 * checked it (see docs/phase-7-auth-keycloak-jwt.md). Single-item reads/writes stay gated on
 * "account-reader"/"account-editor" as before -- ownership (does the caller own THIS account)
 * is a data-dependent check that lives in the service layer instead, not expressible as a
 * static matcher here; see docs/phase-7b-account-ownership-authorization.md. The list-all
 * endpoint moved to "account-admin" in that same phase.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());

        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // Exact "/accounts" (the list) is admin-only -- HEAD included, since Spring MVC
                        // serves HEAD /accounts from the same handler as GET /accounts.
                        .requestMatchers(HttpMethod.GET, "/accounts").hasAuthority("account-admin")
                        .requestMatchers(HttpMethod.HEAD, "/accounts").hasAuthority("account-admin")
                        // Existence-only, no ownership -- see AccountController.accountExists. Placed
                        // ahead of the "/accounts/*" single-segment matcher below for readability, though
                        // the two patterns don't actually overlap (different segment counts). HEAD is
                        // matched explicitly for the same reason as HEAD /accounts/{id} below -- Spring
                        // MVC serves it from the same @GetMapping handler, so without this a HEAD request
                        // fell through to anyRequest().authenticated() and any valid token, not just
                        // account-reader, could probe existence. Found in code review.
                        .requestMatchers(HttpMethod.GET, "/accounts/exists/*").hasAuthority("account-reader")
                        .requestMatchers(HttpMethod.HEAD, "/accounts/exists/*").hasAuthority("account-reader")
                        // HEAD is matched explicitly, not just GET: requestMatchers(GET, ...) does not
                        // match a HEAD request, which Spring MVC still serves from the GET handler --
                        // without this, HEAD /accounts/{id} fell through to anyRequest().authenticated()
                        // and any valid token (not just account-reader) could confirm an account's
                        // existence. Found in code review. Ownership itself is enforced in
                        // AccountService.getAccount, which HEAD reaches too (same handler as GET).
                        .requestMatchers(HttpMethod.GET, "/accounts/*").hasAuthority("account-reader")
                        .requestMatchers(HttpMethod.HEAD, "/accounts/*").hasAuthority("account-reader")
                        .requestMatchers(HttpMethod.POST, "/accounts", "/accounts/*/debit", "/accounts/*/credit")
                        .hasAuthority("account-editor")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
                        .decoder(jwtDecoder)
                        .jwtAuthenticationConverter(converter)));
        return http.build();
    }
}
