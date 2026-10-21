package com.showcase.fraud.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestLoggingFilterTest {

    private final RequestLoggingFilter filter = new RequestLoggingFilter();
    private final Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attachAppender() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    private static FilterChain respondingWith(int status) {
        return (request, response) -> ((MockHttpServletResponse) response).setStatus(status);
    }

    private void run(String method, String uri, int status) throws Exception {
        filter.doFilter(new MockHttpServletRequest(method, uri), new MockHttpServletResponse(), respondingWith(status));
    }

    @Test
    void logsOneLineWithMethodPathStatusAndDuration() throws Exception {
        run("PUT", "/fraud/blocklist/42", 201);

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage()).matches("^PUT /fraud/blocklist/42 201 \\d+ms$");
    }

    @Test
    void leavesOutTheQueryString() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/fraud-check");
        request.setQueryString("accountId=42");

        filter.doFilter(request, new MockHttpServletResponse(), respondingWith(200));

        String message = appender.list.get(0).getFormattedMessage();
        assertThat(message).startsWith("GET /fraud-check 200 ").doesNotContain("?").doesNotContain("accountId=");
    }

    @Test
    void skipsActuatorEndpoints() throws Exception {
        run("GET", "/actuator/health", 200);
        run("GET", "/actuator/prometheus", 200);
        run("GET", "/actuator", 200);

        assertThat(appender.list).isEmpty();
    }

    @Test
    void logsServerErrorsAsWarn() throws Exception {
        run("GET", "/fraud/blocklist/42", 503);

        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
    }

    @Test
    void logsClientErrorsAsInfo() throws Exception {
        run("GET", "/fraud/blocklist/42", 404);

        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.INFO);
    }

    @Test
    void logsA500AndRethrowsWhenTheChainThrows() {
        IllegalStateException boom = new IllegalStateException("boom");
        FilterChain throwing = (request, response) -> {
            throw boom;
        };

        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest("PUT", "/fraud/blocklist/42"),
                new MockHttpServletResponse(), throwing)).isSameAs(boom);
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(appender.list.get(0).getFormattedMessage()).contains(" 500 ");
    }

    @Test
    void attachesKeyValuePairs() throws Exception {
        run("PUT", "/fraud/blocklist/42", 201);

        Map<String, Object> pairs = appender.list.get(0).getKeyValuePairs().stream()
                .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
        assertThat(pairs).containsEntry("http.method", "PUT")
                .containsEntry("http.path", "/fraud/blocklist/42")
                .containsEntry("http.status", 201);
        assertThat((Long) pairs.get("duration_ms")).isNotNegative();
    }
}
