package com.showcase.transfer.config;

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
 * checked it (see docs/phase-7-auth-keycloak-jwt.md). "transfer-executor" covers creating and
 * viewing a single transfer -- only a real end user does either; transfer-service's own
 * machine identity never calls back into itself. The list-all endpoint (GET /transfers, exact
 * path) moved to "transfer-admin" in Phase 7b -- see
 * docs/phase-7b-account-ownership-authorization.md. Ownership on GET /transfers/{id} (does the
 * caller own this transfer) is a data-dependent check that lives in TransferService, not
 * expressible as a static matcher here.
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
                        .requestMatchers("/actuator/prometheus").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // HEAD is matched explicitly alongside each GET matcher: master's blanket
                        // "/transfers/**" pattern covered every HTTP method, but splitting it by
                        // method for the account-admin/transfer-admin split (Phase 7b) dropped HEAD
                        // coverage by omission -- Spring MVC still serves HEAD /transfers and
                        // HEAD /transfers/{id} from the same handlers as their GET counterparts, so
                        // without this both fell through to anyRequest().authenticated(), letting
                        // any valid token bypass the transfer-admin/transfer-executor gate. Found in
                        // code review.
                        .requestMatchers(HttpMethod.GET, "/transfers").hasAuthority("transfer-admin")
                        .requestMatchers(HttpMethod.HEAD, "/transfers").hasAuthority("transfer-admin")
                        .requestMatchers(HttpMethod.GET, "/transfers/*").hasAuthority("transfer-executor")
                        .requestMatchers(HttpMethod.HEAD, "/transfers/*").hasAuthority("transfer-executor")
                        .requestMatchers(HttpMethod.POST, "/transfers").hasAuthority("transfer-executor")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
                        .decoder(jwtDecoder)
                        .jwtAuthenticationConverter(converter)));
        return http.build();
    }
}
