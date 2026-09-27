package com.showcase.fraud.api;

import com.showcase.fraud.domain.BlockedAccountRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The blocklist API against a real database, through the real SecurityConfig. The block and
 * unblock tests read the effect back through /fraud-check, the endpoint Transfer actually calls.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class BlocklistControllerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final RequestPostProcessor FRAUD_ADMIN = jwt().authorities(() -> "fraud-admin");
    private static final RequestPostProcessor FRAUD_CHECKER = jwt().authorities(() -> "fraud-checker");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private BlockedAccountRepository repository;

    @AfterEach
    void clearBlocklist() {
        repository.deleteAllInBatch();
    }

    @Test
    void aBlockedAccountFailsTheFraudCheck() throws Exception {
        UUID accountId = UUID.randomUUID();

        mockMvc.perform(put("/fraud/blocklist/{id}", accountId).with(FRAUD_ADMIN))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/fraud-check").param("accountId", accountId.toString()).with(FRAUD_CHECKER))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_BLOCKED"));
    }

    @Test
    void blockingTwiceIsIdempotent() throws Exception {
        UUID accountId = UUID.randomUUID();

        mockMvc.perform(put("/fraud/blocklist/{id}", accountId).with(FRAUD_ADMIN)).andExpect(status().isNoContent());
        mockMvc.perform(put("/fraud/blocklist/{id}", accountId).with(FRAUD_ADMIN)).andExpect(status().isNoContent());

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void anUnblockedAccountPassesTheFraudCheckAgain() throws Exception {
        UUID accountId = UUID.randomUUID();
        mockMvc.perform(put("/fraud/blocklist/{id}", accountId).with(FRAUD_ADMIN)).andExpect(status().isNoContent());

        mockMvc.perform(delete("/fraud/blocklist/{id}", accountId).with(FRAUD_ADMIN))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/fraud-check").param("accountId", accountId.toString()).with(FRAUD_CHECKER))
                .andExpect(status().isOk());
    }

    @Test
    void unblockingAnAccountThatIsNotBlockedIsAccepted() throws Exception {
        mockMvc.perform(delete("/fraud/blocklist/{id}", UUID.randomUUID()).with(FRAUD_ADMIN))
                .andExpect(status().isNoContent());
    }

    // Review Focus 3: Fraud never asks Account whether the id exists.
    @Test
    void blockingAnUnknownAccountIdIsAccepted() throws Exception {
        UUID neverCreated = UUID.randomUUID();

        mockMvc.perform(put("/fraud/blocklist/{id}", neverCreated).with(FRAUD_ADMIN))
                .andExpect(status().isNoContent());

        assertThat(repository.existsById(neverCreated)).isTrue();
    }

    @Test
    void returns401WithNoToken() throws Exception {
        mockMvc.perform(put("/fraud/blocklist/{id}", UUID.randomUUID())).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/fraud/blocklist/{id}", UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    // Review Focus 4: a customer's token carries fraud-checker (via the customer composite),
    // which must not be enough to change the blocklist.
    @Test
    void returns403ForACustomerToken() throws Exception {
        mockMvc.perform(put("/fraud/blocklist/{id}", UUID.randomUUID()).with(FRAUD_CHECKER))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/fraud/blocklist/{id}", UUID.randomUUID()).with(FRAUD_CHECKER))
                .andExpect(status().isForbidden());
        assertThat(repository.count()).isZero();
    }

    @Test
    void returns400ForAMalformedAccountId() throws Exception {
        mockMvc.perform(put("/fraud/blocklist/{id}", "not-a-uuid").with(FRAUD_ADMIN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }
}
