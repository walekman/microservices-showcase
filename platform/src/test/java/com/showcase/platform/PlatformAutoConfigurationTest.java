package com.showcase.platform;

import com.showcase.platform.logging.RequestLoggingAutoConfiguration;
import com.showcase.platform.logging.RequestLoggingFilter;
import com.showcase.platform.metrics.BuildInfoMetricsAutoConfiguration;
import com.showcase.platform.security.KeycloakJwtAutoConfiguration;
import com.showcase.platform.security.KeycloakProperties;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.OrderUtils;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformAutoConfigurationTest {

    private static final AutoConfigurations ALL = AutoConfigurations.of(RequestLoggingAutoConfiguration.class,
            KeycloakJwtAutoConfiguration.class, BuildInfoMetricsAutoConfiguration.class);

    private static final String[] KEYCLOAK = {"keycloak.issuer-uri=http://localhost:8180/realms/showcase",
            "keycloak.jwk-set-uri=http://keycloak:8080/realms/showcase/protocol/openid-connect/certs"};

    private final WebApplicationContextRunner servlet = new WebApplicationContextRunner()
            .withConfiguration(ALL)
            .withPropertyValues(KEYCLOAK);

    @Test
    void servletServiceGetsEveryPiece() {
        servlet.run(context -> {
            assertThat(context).hasSingleBean(RequestLoggingFilter.class)
                    .hasSingleBean(KeycloakProperties.class)
                    .hasBean("applicationInfoMetrics");
            assertThat(context.getBean(JwtDecoder.class)).isInstanceOf(NimbusJwtDecoder.class);
        });
    }

    @Test
    void keycloakPropertiesBindFromTheKeycloakPrefix() {
        servlet.run(context -> assertThat(context.getBean(KeycloakProperties.class))
                .isEqualTo(new KeycloakProperties("http://localhost:8180/realms/showcase",
                        "http://keycloak:8080/realms/showcase/protocol/openid-connect/certs")));
    }

    @Test
    void requestLogLineKeepsItsOrderBetweenTheObservationFilterAndSecurity() {
        // ServerHttpObservationFilter is HIGHEST_PRECEDENCE + 1, Spring Security's chain -100.
        // This only pins the constant; gateway's RequestLoggingIT and tools/check-trace-logs.ps1
        // prove the real position (the line carries the traceId, and still appears for a 401).
        assertThat(OrderUtils.getOrder(RequestLoggingFilter.class)).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 2);
    }

    @Test
    void requestLogLineCanBeSwitchedOff() {
        servlet.withPropertyValues("showcase.platform.request-logging.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(RequestLoggingFilter.class));
    }

    @Test
    void aServicesOwnJwtDecoderWins() {
        JwtDecoder stub = token -> {
            throw new UnsupportedOperationException();
        };
        servlet.withBean("stubJwtDecoder", JwtDecoder.class, () -> stub)
                .run(context -> assertThat(context.getBean(JwtDecoder.class)).isSameAs(stub));
    }

    @Test
    void aServicesOwnBuildMetricWins() {
        MeterBinder own = registry -> {
        };
        servlet.withBean("applicationInfoMetrics", MeterBinder.class, () -> own)
                .run(context -> assertThat(context.getBean("applicationInfoMetrics")).isSameAs(own));
    }

    @Test
    void serviceWithoutSpringSecurityGetsNoSecurityPieces() {
        // notification-service: no Spring Security on its classpath.
        servlet.withClassLoader(new FilteredClassLoader("org.springframework.security"))
                .run(context -> assertThat(context).doesNotHaveBean(KeycloakProperties.class)
                        .doesNotHaveBean("jwtDecoder")
                        .hasBean("applicationInfoMetrics"));
    }

    @Test
    void nonWebContextGetsNoRequestLogLine() {
        new ApplicationContextRunner().withConfiguration(ALL).withPropertyValues(KEYCLOAK)
                .run(context -> assertThat(context).doesNotHaveBean(RequestLoggingFilter.class));
    }
}
