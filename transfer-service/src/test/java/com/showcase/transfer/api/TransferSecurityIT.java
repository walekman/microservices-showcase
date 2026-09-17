package com.showcase.transfer.api;

import com.showcase.transfer.service.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the REAL SecurityConfig -- not a WebMvcTest slice's default fallback (see
 * TransferControllerTest's comment for why that distinction matters; FraudCheckControllerTest's
 * comment records the bug a WebMvcTest slice hid). TransferService stays mocked so this only
 * exercises the authorization matrix, not the saga; JPA autoconfiguration still needs a real
 * datasource for a full context boot, hence Testcontainers Postgres.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class TransferSecurityIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @org.springframework.beans.factory.annotation.Autowired
    private MockMvc mockMvc;

    @MockBean
    private TransferService transferService;

    @Test
    void returns401WithNoToken() throws Exception {
        mockMvc.perform(get("/transfers"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returns403WithoutTransferExecutorAuthority() throws Exception {
        mockMvc.perform(get("/transfers").with(jwt().authorities(() -> "account-editor")))
                .andExpect(status().isForbidden());
    }

    @Test
    void returns200WithTransferExecutorAuthority() throws Exception {
        mockMvc.perform(get("/transfers").with(jwt().authorities(() -> "transfer-executor")))
                .andExpect(status().isOk());
    }

    @Test
    void actuatorHealthNeedsNoToken() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }
}
