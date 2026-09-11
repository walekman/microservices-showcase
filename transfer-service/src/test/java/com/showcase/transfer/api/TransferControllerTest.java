package com.showcase.transfer.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.showcase.transfer.domain.SameAccountTransferException;
import com.showcase.transfer.domain.Transfer;
import com.showcase.transfer.domain.TransferFailureCode;
import com.showcase.transfer.domain.TransferNotFoundException;
import com.showcase.transfer.service.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransferController.class)
class TransferControllerTest {

    private static final UUID FROM = UUID.randomUUID();
    private static final UUID TO = UUID.randomUUID();
    private static final BigDecimal AMOUNT = new BigDecimal("40.00");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TransferService transferService;

    private String requestBody() throws Exception {
        return objectMapper.writeValueAsString(new CreateTransferRequest(FROM, TO, AMOUNT));
    }

    private Transfer pendingTransfer() {
        return new Transfer(FROM, TO, AMOUNT);
    }

    @Test
    void returns201WhenTheTransferCompletes() throws Exception {
        Transfer transfer = pendingTransfer();
        transfer.markCompleted();
        when(transferService.execute(FROM, TO, AMOUNT)).thenReturn(transfer);

        mockMvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON).content(requestBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void returns422WhenTheTransferFailsForInsufficientFunds() throws Exception {
        Transfer transfer = pendingTransfer();
        transfer.markFailed(TransferFailureCode.INSUFFICIENT_FUNDS, "not enough money");
        when(transferService.execute(FROM, TO, AMOUNT)).thenReturn(transfer);

        mockMvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON).content(requestBody()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.transferStatus").value("FAILED"));
    }

    @Test
    void returns503WhenAccountServiceIsUnavailable() throws Exception {
        Transfer transfer = pendingTransfer();
        transfer.markFailed(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, "connection refused");
        when(transferService.execute(FROM, TO, AMOUNT)).thenReturn(transfer);

        mockMvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON).content(requestBody()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ACCOUNT_SERVICE_UNAVAILABLE"));
    }

    @Test
    void returns500WhenTheTransferNeedsCompensation() throws Exception {
        Transfer transfer = pendingTransfer();
        transfer.markCompensationRequired(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, "credit failed");
        when(transferService.execute(FROM, TO, AMOUNT)).thenReturn(transfer);

        mockMvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON).content(requestBody()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("COMPENSATION_REQUIRED"))
                .andExpect(jsonPath("$.transferStatus").value("COMPENSATION_REQUIRED"));
    }

    @Test
    void returns400ForASelfTransfer() throws Exception {
        when(transferService.execute(any(), any(), any())).thenThrow(new SameAccountTransferException(FROM));

        mockMvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON).content(requestBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SAME_ACCOUNT_TRANSFER"));
    }

    @Test
    void returns400ForANonPositiveAmount() throws Exception {
        String body = objectMapper.writeValueAsString(
                new CreateTransferRequest(FROM, TO, new BigDecimal("0.00")));

        mockMvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void returns404ForAnUnknownTransfer() throws Exception {
        UUID unknown = UUID.randomUUID();
        when(transferService.getTransfer(eq(unknown))).thenThrow(new TransferNotFoundException(unknown));

        mockMvc.perform(get("/transfers/" + unknown))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSFER_NOT_FOUND"));
    }
}
