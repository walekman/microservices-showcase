package com.showcase.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.showcase.gateway.support.TestSecurityConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves RequestLoggingFilter's order through the real servlet filter chain: its line is JSON and
 * carries the request's traceId/spanId (it runs inside ServerHttpObservationFilter), and a request
 * Spring Security rejects is still logged (it runs before the security chain). Needs no upstream:
 * the request never gets past security.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestSecurityConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class RequestLoggingIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void requestLineIsJsonInsideTheRequestTrace(CapturedOutput output) throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/transfers/123", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        String line = Arrays.stream(output.getOut().split("\\R"))
                .filter(candidate -> candidate.contains("GET /transfers/123 401"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No request log line for GET /transfers/123"));
        JsonNode node = new ObjectMapper().readTree(line);
        assertThat(node.get("level").asText()).isEqualTo("INFO");
        assertThat(node.path("traceId").asText()).isNotBlank();
        assertThat(node.path("spanId").asText()).isNotBlank();
        assertThat(node.path("http.status").asInt()).isEqualTo(401);
    }
}
