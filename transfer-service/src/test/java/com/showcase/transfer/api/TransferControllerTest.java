package com.showcase.transfer.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.showcase.transfer.domain.DebitOutcomeUnknownException;
import com.showcase.transfer.domain.IdempotencyKeyConflictException;
import com.showcase.transfer.domain.SameAccountTransferException;
import com.showcase.transfer.domain.Transfer;
import com.showcase.transfer.domain.TransferFailureCode;
import com.showcase.transfer.domain.TransferInProgressException;
import com.showcase.transfer.domain.TransferNotFoundException;
import com.showcase.transfer.domain.TransferStatus;
import com.showcase.transfer.service.TransferPersistenceException;
import com.showcase.transfer.service.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// A @WebMvcTest slice does not reliably include a hand-written SecurityConfig's
// SecurityFilterChain bean (see FraudCheckControllerTest's comment) -- it falls back to Spring
// Boot's own "any authenticated request" default, so jwt() with any authority satisfies every
// test below regardless of which authority is granted. That's fine here: this class tests
// controller/error-mapping logic, not the authorization matrix itself -- the real
// hasAuthority("transfer-executor") enforcement is covered by TransferSecurityIT's full context.
@WebMvcTest(TransferController.class)
class TransferControllerTest {

    private static final UUID FROM = UUID.randomUUID();
    private static final UUID TO = UUID.randomUUID();
    private static final BigDecimal AMOUNT = new BigDecimal("40.00");
    // A stand-in for the caller's JWT sub. This class only tests controller/error-mapping
    // logic with TransferService mocked out, so its exact value never matters beyond being a
    // valid UUID string -- the real ownership behaviour is TransferServiceTest's job.
    private static final UUID SUBJECT = UUID.randomUUID();
    private static final String KEY = "client-key-1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TransferService transferService;

    private static RequestPostProcessor transferExecutor() {
        return jwt().jwt(builder -> builder.subject(SUBJECT.toString())).authorities(() -> "transfer-executor");
    }

    private String requestBody() throws Exception {
        return objectMapper.writeValueAsString(new CreateTransferRequest(FROM, TO, AMOUNT));
    }

    private Transfer pendingTransfer() {
        return new Transfer(FROM, TO, AMOUNT, SUBJECT);
    }

    @Test
    void returns201WhenTheTransferCompletes() throws Exception {
        Transfer transfer = pendingTransfer();
        transfer.markCompleted();
        when(transferService.execute(FROM, TO, AMOUNT, SUBJECT, KEY)).thenReturn(transfer);

        mockMvc.perform(post("/transfers").header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(requestBody()).with(transferExecutor()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void returns422WhenTheTransferFailsForInsufficientFunds() throws Exception {
        Transfer transfer = pendingTransfer();
        transfer.markFailed(TransferFailureCode.INSUFFICIENT_FUNDS, "not enough money");
        when(transferService.execute(FROM, TO, AMOUNT, SUBJECT, KEY)).thenReturn(transfer);

        mockMvc.perform(post("/transfers").header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(requestBody()).with(transferExecutor()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.transferStatus").value("FAILED"));
    }

    @Test
    void returns503WhenAccountServiceIsUnavailable() throws Exception {
        Transfer transfer = pendingTransfer();
        transfer.markFailed(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, "connection refused");
        when(transferService.execute(FROM, TO, AMOUNT, SUBJECT, KEY)).thenReturn(transfer);

        mockMvc.perform(post("/transfers").header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(requestBody()).with(transferExecutor()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ACCOUNT_SERVICE_UNAVAILABLE"))
                // The recorded reason can name the internal host and port of Account Service.
                // The caller gets a fixed sentence; the real reason stays in the log and the row.
                .andExpect(jsonPath("$.detail").value("Account Service is currently unavailable"))
                .andExpect(jsonPath("$.detail", not(containsString("connection refused"))));
    }

    @Test
    void returns503WhenNoExchangeRateIsAvailable() throws Exception {
        Transfer transfer = pendingTransfer();
        transfer.markFailed(TransferFailureCode.FX_SERVICE_UNAVAILABLE, "FX Service returned 503 from fx-service:8085");
        when(transferService.execute(FROM, TO, AMOUNT, SUBJECT, KEY)).thenReturn(transfer);

        mockMvc.perform(post("/transfers").header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(requestBody()).with(transferExecutor()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("FX_SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.detail").value("FX Service is currently unavailable"));
    }

    @Test
    void returns422WhenTheConvertedAmountRoundsToNothing() throws Exception {
        Transfer transfer = pendingTransfer();
        transfer.markFailed(TransferFailureCode.AMOUNT_TOO_SMALL, "0.01 PLN converts to 0.00 EUR at rate 0.22819");
        when(transferService.execute(FROM, TO, AMOUNT, SUBJECT, KEY)).thenReturn(transfer);

        mockMvc.perform(post("/transfers").header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(requestBody()).with(transferExecutor()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AMOUNT_TOO_SMALL"))
                .andExpect(jsonPath("$.detail").value("0.01 PLN converts to 0.00 EUR at rate 0.22819"));
    }

    @Test
    void theResponseCarriesTheLockedConversion() throws Exception {
        Transfer transfer = pendingTransfer();
        transfer.lockConversion("PLN", "EUR", new BigDecimal("0.22819"), java.time.LocalDate.of(2026, 9, 23));
        transfer.markCompleted();
        when(transferService.execute(FROM, TO, AMOUNT, SUBJECT, KEY)).thenReturn(transfer);

        mockMvc.perform(post("/transfers").header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(requestBody()).with(transferExecutor()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceCurrency").value("PLN"))
                .andExpect(jsonPath("$.destinationCurrency").value("EUR"))
                .andExpect(jsonPath("$.rate").value(0.22819))
                .andExpect(jsonPath("$.rateAsOf").value("2026-09-23"))
                .andExpect(jsonPath("$.creditAmount").value(9.13));
    }

    @Test
    void doesNotLeakInternalExceptionTextForAnUnexpectedError() throws Exception {
        Transfer transfer = pendingTransfer();
        transfer.markFailed(TransferFailureCode.UNEXPECTED_ERROR,
                "java.lang.IllegalStateException: response mapper exploded");
        when(transferService.execute(FROM, TO, AMOUNT, SUBJECT, KEY)).thenReturn(transfer);

        mockMvc.perform(post("/transfers").header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(requestBody()).with(transferExecutor()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("UNEXPECTED_ERROR"))
                .andExpect(jsonPath("$.detail").value("The transfer could not be completed due to an internal error"))
                .andExpect(jsonPath("$.detail", not(containsString("IllegalStateException"))));
    }

    @Test
    void keepsTheRealReasonForBusinessFailures() throws Exception {
        Transfer transfer = pendingTransfer();
        transfer.markFailed(TransferFailureCode.INSUFFICIENT_FUNDS, "Balance 10.00 is less than 40.00");
        when(transferService.execute(FROM, TO, AMOUNT, SUBJECT, KEY)).thenReturn(transfer);

        // The counterpart to the two tests above: a business reason is written for the caller
        // and contains nothing internal, so blanket sanitising would throw away the one thing
        // that tells them what to do next.
        mockMvc.perform(post("/transfers").header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(requestBody()).with(transferExecutor()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Balance 10.00 is less than 40.00"));
    }

    @Test
    void returns500WithTheTransferIdWhenTheTransferIsNotInATerminalState() throws Exception {
        // Unreachable through TransferService today, which always settles a transfer before
        // returning it. Guarded anyway: a PENDING transfer has no failure code, and reading
        // one would NPE into the catch-all and lose the id -- the one property a caller
        // needs to find the row and see what really happened.
        when(transferService.execute(FROM, TO, AMOUNT, SUBJECT, KEY)).thenReturn(pendingTransfer());

        mockMvc.perform(post("/transfers").header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(requestBody()).with(transferExecutor()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("UNEXPECTED_ERROR"))
                .andExpect(jsonPath("$.transferStatus").value("PENDING"))
                .andExpect(jsonPath("$.detail").value("The transfer could not be completed due to an internal error"));
    }

    @Test
    void returns500WhenTheTransferNeedsCompensation() throws Exception {
        Transfer transfer = pendingTransfer();
        transfer.markCompensationRequired(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, "credit failed");
        when(transferService.execute(FROM, TO, AMOUNT, SUBJECT, KEY)).thenReturn(transfer);

        mockMvc.perform(post("/transfers").header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(requestBody()).with(transferExecutor()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("COMPENSATION_REQUIRED"))
                .andExpect(jsonPath("$.transferStatus").value("COMPENSATION_REQUIRED"))
                // Sanitising is keyed on the failure code, not the status: a stranded transfer
                // carries the same operational text as a failed one, so it leaks the same way.
                .andExpect(jsonPath("$.detail").value("Account Service is currently unavailable"));
    }

    @Test
    void returns500WithTheTransferIdWhenTheTerminalStateCannotBePersisted() throws Exception {
        // The saga ran but failed to write its outcome, so a row exists and still reads PENDING.
        // Without the id in this body the caller cannot find the one record that needs
        // reconciling -- the same hole the COMPENSATION_REQUIRED 500 exists to close.
        UUID id = UUID.randomUUID();
        when(transferService.execute(FROM, TO, AMOUNT, SUBJECT, KEY)).thenThrow(
                new TransferPersistenceException(id, new IllegalStateException("version conflict")));

        mockMvc.perform(post("/transfers").header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(requestBody()).with(transferExecutor()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.transferId").value(id.toString()))
                .andExpect(jsonPath("$.detail", not(containsString("version conflict"))));
    }

    @Test
    void returns400ForASelfTransfer() throws Exception {
        when(transferService.execute(any(), any(), any(), any(), any())).thenThrow(new SameAccountTransferException(FROM));

        mockMvc.perform(post("/transfers").header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(requestBody()).with(transferExecutor()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SAME_ACCOUNT_TRANSFER"));
    }

    @Test
    void returns400ForANonPositiveAmount() throws Exception {
        String body = objectMapper.writeValueAsString(
                new CreateTransferRequest(FROM, TO, new BigDecimal("0.00")));

        mockMvc.perform(post("/transfers").header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(body).with(transferExecutor()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void returns400WhenTheIdempotencyKeyIsMissing() throws Exception {
        mockMvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON).content(requestBody()).with(transferExecutor()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail").value("Missing required header: Idempotency-Key"));
        verifyNoInteractions(transferService);
    }

    @Test
    void returns400ForABlankOrOverlongIdempotencyKey() throws Exception {
        for (String key : List.of(" ", "k".repeat(256))) {
            mockMvc.perform(post("/transfers").header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON).content(requestBody()).with(transferExecutor()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        verifyNoInteractions(transferService);
    }

    @Test
    void returns409WhenTheIdempotencyKeyWasUsedForADifferentTransfer() throws Exception {
        when(transferService.execute(FROM, TO, AMOUNT, SUBJECT, KEY)).thenThrow(new IdempotencyKeyConflictException(KEY));

        mockMvc.perform(post("/transfers").header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(requestBody()).with(transferExecutor()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
    }

    @Test
    void returns503WithTheTransferIdAndPendingStatusWhenTheDebitOutcomeIsUnknown() throws Exception {
        UUID id = UUID.randomUUID();
        when(transferService.execute(FROM, TO, AMOUNT, SUBJECT, KEY))
                .thenThrow(new DebitOutcomeUnknownException(id, new RuntimeException("read timed out")));

        mockMvc.perform(post("/transfers").header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(requestBody()).with(transferExecutor()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ACCOUNT_SERVICE_UNAVAILABLE"))
                // PENDING is what tells the caller (and the Bank UI) to keep the same
                // Idempotency-Key: the transfer is not settled and may still complete.
                .andExpect(jsonPath("$.transferId").value(id.toString()))
                .andExpect(jsonPath("$.transferStatus").value("PENDING"))
                .andExpect(jsonPath("$.detail", not(containsString("read timed out"))));
    }

    @Test
    void returns409WithTheTransferIdWhenTheOriginalIsStillInProgress() throws Exception {
        UUID id = UUID.randomUUID();
        when(transferService.execute(FROM, TO, AMOUNT, SUBJECT, KEY)).thenThrow(new TransferInProgressException(id));

        mockMvc.perform(post("/transfers").header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(requestBody()).with(transferExecutor()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSFER_IN_PROGRESS"))
                .andExpect(jsonPath("$.transferId").value(id.toString()))
                .andExpect(jsonPath("$.transferStatus").value("PENDING"));
    }

    @Test
    void returns404ForAnUnknownTransfer() throws Exception {
        UUID unknown = UUID.randomUUID();
        when(transferService.getTransfer(eq(unknown), any())).thenThrow(new TransferNotFoundException(unknown));

        mockMvc.perform(get("/transfers/" + unknown).with(transferExecutor()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSFER_NOT_FOUND"));
    }

    @Test
    void returns503WhenAccountIsUnavailableDuringTheOwnershipCheck() throws Exception {
        // GET /transfers/{id}'s destination-owner check (AccountClient.isOwnedByCaller) can
        // propagate AccountServiceUnavailableException -- this must map to the project's usual
        // 503, not fall through to a generic 500. Found in code review.
        UUID id = UUID.randomUUID();
        when(transferService.getTransfer(eq(id), any()))
                .thenThrow(new com.showcase.transfer.client.AccountServiceUnavailableException("read timed out"));

        mockMvc.perform(get("/transfers/" + id).with(transferExecutor()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ACCOUNT_SERVICE_UNAVAILABLE"));
    }

    @Test
    void returnsTheTransferById() throws Exception {
        Transfer transfer = pendingTransfer();
        transfer.markCompleted();
        UUID id = UUID.randomUUID();
        when(transferService.getTransfer(id, SUBJECT)).thenReturn(transfer);

        mockMvc.perform(get("/transfers/" + id).with(transferExecutor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.fromAccountId").value(FROM.toString()))
                .andExpect(jsonPath("$.amount").value(40.00));
    }

    @Test
    void listsTransfersFilteredByStatus() throws Exception {
        Transfer transfer = pendingTransfer();
        transfer.markFailed(TransferFailureCode.INSUFFICIENT_FUNDS, "not enough money");
        when(transferService.listTransfers(TransferStatus.FAILED)).thenReturn(List.of(transfer));

        mockMvc.perform(get("/transfers?status=FAILED").with(transferExecutor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("FAILED"))
                .andExpect(jsonPath("$[0].failureCode").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    void listsEveryTransferWhenNoStatusIsGiven() throws Exception {
        mockMvc.perform(get("/transfers").with(transferExecutor()))
                .andExpect(status().isOk());

        // Pins the binding, not the service: an absent ?status= must reach the service as null,
        // which is what it reads as "no filter". A default value creeping into the @RequestParam
        // would quietly turn this endpoint into a filtered one.
        verify(transferService).listTransfers(null);
    }

    @Test
    void returns400ForAnUnknownStatusValue() throws Exception {
        mockMvc.perform(get("/transfers?status=BOGUS").with(transferExecutor()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void returns405WithCodeForUnsupportedMethod() throws Exception {
        // Covers the exceptions ResponseEntityExceptionHandler handles for us: they render through
        // handleExceptionInternal, which must stamp the code/timestamp invariant on every problem body.
        mockMvc.perform(delete("/transfers/" + UUID.randomUUID()).with(transferExecutor()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("REQUEST_REJECTED"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void listsMyTransfersUsingTheCallersSubject() throws Exception {
        Transfer transfer = pendingTransfer();
        transfer.markCompleted();
        when(transferService.listMyTransfers(SUBJECT, null)).thenReturn(List.of(transfer));

        mockMvc.perform(get("/transfers/mine").with(transferExecutor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("COMPLETED"));
    }

    @Test
    void marksEachOfMyTransfersWithItsDirectionForTheCaller() throws Exception {
        Transfer sent = pendingTransfer();
        Transfer received = new Transfer(TO, FROM, AMOUNT, UUID.randomUUID());
        received.markCompleted();
        when(transferService.listMyTransfers(SUBJECT, null)).thenReturn(List.of(sent, received));

        mockMvc.perform(get("/transfers/mine").with(transferExecutor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].direction").value("OUTGOING"))
                .andExpect(jsonPath("$[1].direction").value("INCOMING"));
    }

    @Test
    void omitsDirectionOutsideTheCallerScopedList() throws Exception {
        // Direction only means something relative to "me"; the admin list has no "me".
        when(transferService.listTransfers(null)).thenReturn(List.of(pendingTransfer()));

        mockMvc.perform(get("/transfers").with(transferExecutor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].direction").doesNotExist());
    }

    @Test
    void returns503WhenAccountIsUnavailableListingMyTransfers() throws Exception {
        when(transferService.listMyTransfers(SUBJECT, null))
                .thenThrow(new com.showcase.transfer.client.AccountServiceUnavailableException("read timed out"));

        mockMvc.perform(get("/transfers/mine").with(transferExecutor()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ACCOUNT_SERVICE_UNAVAILABLE"));
    }
}
