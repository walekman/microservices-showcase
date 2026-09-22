package com.showcase.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.util.ServletRequestPathUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayRoutesConfigTest {

    private final GatewayRoutesConfig config = new GatewayRoutesConfig();
    private final RouterFunction<ServerResponse> transferRoutes =
            config.transferRoutes(new TransferServiceProperties("http://transfer-service:8082"));
    private final RouterFunction<ServerResponse> accountRoutes =
            config.accountRoutes(new AccountServiceProperties("http://account-service:8081"));

    /**
     * Checks only whether a route predicate matches -- RouterFunction.route() never invokes
     * the handler, so this never makes a real network call and needs no backend running.
     */
    private static boolean matches(RouterFunction<ServerResponse> routes, String method, String path) {
        MockHttpServletRequest mockRequest = new MockHttpServletRequest(method, path);
        ServletRequestPathUtils.parseAndCache(mockRequest);
        List<HttpMessageConverter<?>> converters = List.of();
        ServerRequest request = ServerRequest.create(mockRequest, converters);
        return routes.route(request).isPresent();
    }

    @Test
    void transfersPathMatchesAnyMethod() {
        assertThat(matches(transferRoutes, "POST", "/transfers")).isTrue();
        assertThat(matches(transferRoutes, "GET", "/transfers")).isTrue();
        assertThat(matches(transferRoutes, "GET", "/transfers/123")).isTrue();
    }

    @Test
    void accountCreateAndListMatch() {
        assertThat(matches(accountRoutes, "POST", "/accounts")).isTrue();
        assertThat(matches(accountRoutes, "GET", "/accounts")).isTrue();
    }

    @Test
    void accountGetByIdMatches() {
        assertThat(matches(accountRoutes, "GET", "/accounts/123")).isTrue();
    }

    @Test
    void accountDebitAndCreditHaveNoRoute() {
        assertThat(matches(accountRoutes, "POST", "/accounts/123/debit")).isFalse();
        assertThat(matches(accountRoutes, "POST", "/accounts/123/credit")).isFalse();
    }

    @Test
    void unrelatedPathsHaveNoRoute() {
        assertThat(matches(transferRoutes, "GET", "/fraud-check")).isFalse();
        assertThat(matches(accountRoutes, "GET", "/fraud-check")).isFalse();
    }

    @Test
    void accountMineMatches() {
        assertThat(matches(accountRoutes, "GET", "/accounts/mine")).isTrue();
    }

    @Test
    void accountSummaryMatches() {
        assertThat(matches(accountRoutes, "GET", "/accounts/123/summary")).isTrue();
    }
}
