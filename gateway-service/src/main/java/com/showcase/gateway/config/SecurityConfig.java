package com.showcase.gateway.config;

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
 * Validates the JWT here too, not just downstream -- this is the FIRST check, not the only
 * one (Account/Transfer/Fraud each independently re-validate, see
 * docs/phase-7-auth-keycloak-jwt.md). The proxy hop forwards the Authorization header
 * unchanged, so a request that passes this filter still carries its token to whichever
 * service it's routed to.
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
                        // hasAnyAuthority, not hasAuthority: this is the coarse first check (see
                        // class javadoc), not the precise one -- GET /transfers (list) needs
                        // transfer-admin downstream, POST /transfers and GET /transfers/{id}
                        // need transfer-executor, but the Gateway doesn't split by method/path
                        // here, so it admits either and lets transfer-service's own SecurityConfig
                        // enforce the exact split. Without account-admin/transfer-admin here, a
                        // Phase 7b admin token would 403 at the Gateway before ever reaching the
                        // service that's supposed to authorize it -- found in code review.
                        .requestMatchers("/transfers/**").hasAnyAuthority("transfer-executor", "transfer-admin")
                        .requestMatchers(HttpMethod.GET, "/accounts", "/accounts/*")
                        .hasAnyAuthority("account-reader", "account-admin")
                        .requestMatchers(HttpMethod.POST, "/accounts").hasAuthority("account-editor")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
                        .decoder(jwtDecoder)
                        .jwtAuthenticationConverter(converter)));
        return http.build();
    }
}
