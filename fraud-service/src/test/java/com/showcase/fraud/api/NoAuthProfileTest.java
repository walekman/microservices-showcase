package com.showcase.fraud.api;

import com.showcase.fraud.service.BlocklistService;
import com.showcase.fraud.service.FraudCheckService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The "no-auth" profile's open chain; FraudCheckControllerTest and BlocklistControllerIT cover
// the default 401/403.
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("no-auth")
class NoAuthProfileTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FraudCheckService fraudCheckService;

    @MockitoBean
    private BlocklistService blocklistService;

    @Test
    void fraudCheckNeedsNoToken() throws Exception {
        mockMvc.perform(get("/fraud-check").param("accountId", ACCOUNT_ID.toString()))
                .andExpect(status().isOk());
    }

    @Test
    void blocklistNeedsNoToken() throws Exception {
        mockMvc.perform(delete("/fraud/blocklist/{id}", ACCOUNT_ID))
                .andExpect(status().is2xxSuccessful());
    }
}
