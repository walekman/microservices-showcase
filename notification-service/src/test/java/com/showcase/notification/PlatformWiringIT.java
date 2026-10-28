package com.showcase.notification;

import com.showcase.platform.logging.RequestLoggingFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.ClassUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * notification-service takes only the build metric from the platform module (BuildInfoIT checks
 * that one). This guards what it must not get: Spring Security on its classpath, the request
 * log line or the generic error handling it has no business endpoints for
 * (docs/phase-14-shared-platform-module.md).
 */
@Testcontainers
@SpringBootTest
class PlatformWiringIT {

    @Container
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.0");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private ApplicationContext context;

    @Test
    void springSecurityIsNotOnTheClasspath() {
        assertThat(ClassUtils.isPresent("org.springframework.security.core.Authentication", null)).isFalse();
    }

    @Test
    void noRequestLogLine() {
        assertThat(context.getBeansOfType(RequestLoggingFilter.class)).isEmpty();
    }

    @Test
    void noGenericErrorHandling() {
        assertThat(context.getBeansOfType(ResponseEntityExceptionHandler.class)).isEmpty();
    }
}
