package com.showcase.account.api;

import com.showcase.account.service.AccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the REAL per-route authorization matrix -- not a WebMvcTest slice's default fallback
 * (see FraudCheckControllerTest's comment for why that distinction matters). AccountService
 * stays mocked so this only exercises the authorization matrix, not the business logic; JPA
 * autoconfiguration still needs a real datasource for a full context boot, hence Testcontainers
 * Postgres.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AccountSecurityIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AccountService accountService;

    @Test
    void getAccountsReturns401WithNoToken() throws Exception {
        mockMvc.perform(get("/accounts"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getAccountsReturns403WithoutAccountReaderAuthority() throws Exception {
        mockMvc.perform(get("/accounts").with(jwt().authorities(() -> "account-editor")))
                .andExpect(status().isForbidden());
    }

    @Test
    void getAccountsReturns200WithAccountReaderAuthority() throws Exception {
        mockMvc.perform(get("/accounts").with(jwt().authorities(() -> "account-reader")))
                .andExpect(status().isOk());
    }

    @Test
    void postAccountsReturns403WithoutAccountEditorAuthority() throws Exception {
        mockMvc.perform(post("/accounts")
                        .contentType("application/json")
                        .content("{\"ownerName\":\"Ada\",\"initialBalance\":10.00}")
                        .with(jwt().authorities(() -> "account-reader")))
                .andExpect(status().isForbidden());
    }

    @Test
    void actuatorHealthNeedsNoToken() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }
}
