// transfer-service/src/test/java/com/showcase/transfer/StructuredLoggingIT.java
package com.showcase.transfer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class StructuredLoggingIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final Logger log = LoggerFactory.getLogger(StructuredLoggingIT.class);

    @Autowired
    private Tracer tracer;

    @Test
    void logLineInsideASpanIsJsonWithTraceContext(CapturedOutput output) throws IOException {
        io.micrometer.tracing.Span span = tracer.nextSpan().name("test-span").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            log.info("structured logging probe");
        } finally {
            span.end();
        }

        ObjectMapper mapper = new ObjectMapper();
        String jsonLine = Arrays.stream(output.getOut().split("\\R"))
                .filter(line -> line.contains("structured logging probe"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No captured log line contains the probe message"));

        JsonNode node = mapper.readTree(jsonLine);
        assertThat(node.has("traceId")).isTrue();
        assertThat(node.has("spanId")).isTrue();
        assertThat(node.get("traceId").asText()).isNotBlank();
    }
}
