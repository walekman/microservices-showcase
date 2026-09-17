package com.showcase.transfer.api;

import com.showcase.transfer.domain.Transfer;
import com.showcase.transfer.domain.TransferNotFoundException;
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

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
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
    void returns403WithoutTransferAdminAuthority() throws Exception {
        mockMvc.perform(get("/transfers").with(jwt().authorities(() -> "account-editor")))
                .andExpect(status().isForbidden());
    }

    // Phase 7b moved the list-all endpoint from transfer-executor to transfer-admin -- an
    // ordinary customer is no longer enough, even one holding transfer-executor.
    @Test
    void returns403ForAnOrdinaryCustomerWithoutTransferAdminAuthority() throws Exception {
        mockMvc.perform(get("/transfers").with(jwt().authorities(() -> "transfer-executor")))
                .andExpect(status().isForbidden());
    }

    @Test
    void returns200WithTransferAdminAuthority() throws Exception {
        mockMvc.perform(get("/transfers").with(jwt().authorities(() -> "transfer-admin")))
                .andExpect(status().isOk());
    }

    @Test
    void getTransferReturns401WithNoToken() throws Exception {
        mockMvc.perform(get("/transfers/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getTransferReturns403WithoutTransferExecutorAuthority() throws Exception {
        mockMvc.perform(get("/transfers/" + UUID.randomUUID()).with(jwt().authorities(() -> "account-editor")))
                .andExpect(status().isForbidden());
    }

    @Test
    void getTransferPassesTheCallersSubjectToTheService() throws Exception {
        UUID id = UUID.randomUUID();
        UUID subject = UUID.randomUUID();
        Transfer transfer = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), subject);
        when(transferService.getTransfer(id, subject)).thenReturn(transfer);

        mockMvc.perform(get("/transfers/" + id)
                        .with(jwt().jwt(builder -> builder.subject(subject.toString()))
                                .authorities(() -> "transfer-executor")))
                .andExpect(status().isOk());
    }

    @Test
    void getTransferReturns404WhenTheServiceRejectsOwnership() throws Exception {
        // TransferService.getTransfer reuses TransferNotFoundException for a caller who is
        // neither the initiator nor the destination owner -- see
        // docs/phase-7b-account-ownership-authorization.md's Design Decisions.
        UUID id = UUID.randomUUID();
        when(transferService.getTransfer(eq(id), any())).thenThrow(new TransferNotFoundException(id));

        mockMvc.perform(get("/transfers/" + id)
                        .with(jwt().jwt(builder -> builder.subject(UUID.randomUUID().toString()))
                                .authorities(() -> "transfer-executor")))
                .andExpect(status().isNotFound());
    }

    @Test
    void actuatorHealthNeedsNoToken() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }
}
