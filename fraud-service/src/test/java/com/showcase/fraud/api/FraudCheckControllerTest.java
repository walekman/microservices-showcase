package com.showcase.fraud.api;

import com.showcase.fraud.domain.AccountBlockedException;
import com.showcase.fraud.service.FraudCheckService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// @SpringBootTest + @AutoConfigureMockMvc, not @WebMvcTest: a @WebMvcTest slice does not
// reliably include a hand-written @Configuration class's SecurityFilterChain bean, so it
// silently falls back to Spring Boot's own auto-configured "any authenticated request" default
// -- which has no notion of this service's per-path hasAuthority(...) rules at all. Found live:
// a token with the wrong authority (e.g. "account-editor" on this fraud-checker-only endpoint)
// returned 200 under @WebMvcTest instead of 403. Fraud Service has no database/Kafka, so a full
// context boot here needs no Testcontainers/Docker.
@SpringBootTest
@AutoConfigureMockMvc
class FraudCheckControllerTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FraudCheckService fraudCheckService;

    @Test
    void returns200WhenTheAccountIsClear() throws Exception {
        mockMvc.perform(get("/fraud-check").param("accountId", ACCOUNT_ID.toString())
                        .with(jwt().authorities(() -> "fraud-checker")))
                .andExpect(status().isOk());
    }

    @Test
    void returns422WithAccountBlockedCodeWhenTheServiceRejects() throws Exception {
        doThrow(new AccountBlockedException(ACCOUNT_ID)).when(fraudCheckService).check(ACCOUNT_ID);

        mockMvc.perform(get("/fraud-check").param("accountId", ACCOUNT_ID.toString())
                        .with(jwt().authorities(() -> "fraud-checker")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_BLOCKED"));
    }

    @Test
    void returns400WhenAccountIdIsMissing() throws Exception {
        mockMvc.perform(get("/fraud-check").with(jwt().authorities(() -> "fraud-checker")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void returns400WhenAccountIdIsMalformed() throws Exception {
        mockMvc.perform(get("/fraud-check").param("accountId", "not-a-uuid")
                        .with(jwt().authorities(() -> "fraud-checker")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void returns401WithNoToken() throws Exception {
        mockMvc.perform(get("/fraud-check").param("accountId", ACCOUNT_ID.toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returns403WithoutTheFraudCheckerAuthority() throws Exception {
        mockMvc.perform(get("/fraud-check").param("accountId", ACCOUNT_ID.toString())
                        .with(jwt().authorities(() -> "account-editor")))
                .andExpect(status().isForbidden());
    }
}
