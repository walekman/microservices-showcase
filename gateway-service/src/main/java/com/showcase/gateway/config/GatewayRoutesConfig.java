package com.showcase.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RequestPredicate;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;
import static org.springframework.web.servlet.function.RequestPredicates.GET;
import static org.springframework.web.servlet.function.RequestPredicates.POST;
import static org.springframework.web.servlet.function.RequestPredicates.path;

/**
 * Allow-list routing: only the predicates declared here are reachable through the Gateway.
 * Account's debit/credit endpoints (and every Fraud/Notification path, and everything on FX
 * Service except GET /fx/rates) have no route at all, so they 404 by construction instead of relying on a blocklist filter that could go
 * stale as Account's API grows. See docs/phase-6-api-gateway.md's Design Decisions.
 */
@Configuration
public class GatewayRoutesConfig {

    @Bean
    public RouterFunction<ServerResponse> transferRoutes(TransferServiceProperties properties) {
        return route(path("/transfers/**"), http(properties.baseUrl()));
    }

    @Bean
    public RouterFunction<ServerResponse> accountRoutes(AccountServiceProperties properties) {
        RequestPredicate accountPaths = POST("/accounts")
                .or(GET("/accounts"))
                .or(GET("/accounts/mine"))
                .or(GET("/accounts/{id}"))
                .or(GET("/accounts/{id}/summary"));
        return route(accountPaths, http(properties.baseUrl()));
    }

    // Only the one read the Bank UI needs for a quote. Nothing else FX Service exposes is reachable.
    @Bean
    public RouterFunction<ServerResponse> fxRoutes(FxServiceProperties properties) {
        return route(GET("/fx/rates"), http(properties.baseUrl()));
    }
}
