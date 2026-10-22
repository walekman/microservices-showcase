package com.showcase.fraud.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * One log line per HTTP request ({@code POST /transfers 201 184ms}), so every service a request
 * passes through shows up in that trace's logs in Grafana (docs/phase-13-log-aggregation-loki.md).
 * Never logs the query string, headers or bodies. Actuator is skipped: healthchecks and scrapes
 * would bury the real requests.
 *
 * <p>Ordered right after Boot's ServerHttpObservationFilter ({@code HIGHEST_PRECEDENCE + 1}), so
 * the request's span is current and the line carries its traceId, and before Spring Security's
 * filter chain ({@code -100}), so 401/403 responses are logged too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.equals("/actuator") || uri.startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        boolean threw = true;
        try {
            chain.doFilter(request, response);
            threw = false;
        } finally {
            int status = threw ? 500 : response.getStatus();
            long millis = (System.nanoTime() - start) / 1_000_000;
            log.atLevel(status >= 500 ? Level.WARN : Level.INFO)
                    .addKeyValue("http.method", request.getMethod())
                    .addKeyValue("http.path", request.getRequestURI())
                    .addKeyValue("http.status", status)
                    .addKeyValue("duration_ms", millis)
                    .log("{} {} {} {}ms", request.getMethod(), request.getRequestURI(), status, millis);
        }
    }
}
