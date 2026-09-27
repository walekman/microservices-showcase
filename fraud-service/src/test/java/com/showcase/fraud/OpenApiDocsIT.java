package com.showcase.fraud;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guards the OpenAPI spec behind Swagger UI. /swagger-ui/index.html is static and returns 200
 * even when springdoc cannot build the spec, so only fetching /v3/api-docs proves Swagger works.
 * The Boot 3.3.8 -> 3.5.16 bump left springdoc 2.6.0 in place and every spec request failed with
 * a NoSuchMethodError from springdoc's scan of the @ControllerAdvice beans, unnoticed because no
 * test requested the spec. A full-context test, not a WebMvcTest slice: the slice would not
 * load the real SecurityConfig that makes the spec reachable without a token.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocsIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void apiDocsAreServedWithoutATokenAndDescribeTheRealEndpoints() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").value(startsWith("3.")))
                .andExpect(jsonPath("$.paths['/fraud-check']").exists())
                .andExpect(jsonPath("$.paths['/fraud/blocklist/{accountId}'].put").exists())
                .andExpect(jsonPath("$.paths['/fraud/blocklist/{accountId}'].delete").exists())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"));
    }
}
