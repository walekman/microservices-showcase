package com.showcase.account.api;

import com.showcase.account.domain.Account;
import com.showcase.account.domain.AccountNotFoundException;
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

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the REAL per-route authorization matrix -- not a WebMvcTest slice's default fallback
 * (see FraudCheckControllerTest's comment for why that distinction matters). AccountService
 * stays mocked so this only exercises the authorization matrix and the JWT-to-service-argument
 * wiring, not the ownership business logic itself (that's AccountServiceTest's job, against a
 * mocked repository); JPA autoconfiguration still needs a real datasource for a full context
 * boot, hence Testcontainers Postgres.
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

    // Phase 7b moved the list-all endpoint from account-reader to account-admin -- an ordinary
    // customer (even one holding account-editor) is no longer enough.
    @Test
    void getAccountsReturns403WithoutAccountAdminAuthority() throws Exception {
        mockMvc.perform(get("/accounts").with(jwt().authorities(() -> "account-reader")))
                .andExpect(status().isForbidden());
    }

    // HEAD is matched explicitly in SecurityConfig alongside every GET matcher in this class --
    // Spring MVC serves HEAD from the same handler as its GET counterpart, so a missing HEAD
    // matcher lets any valid token bypass the role gate a moment before this comment was
    // written. Found in code review (twice now -- see SecurityConfig's own comments); these
    // tests exist so a future refactor that re-drops HEAD coverage fails loudly instead of
    // silently.
    @Test
    void headAccountsReturns403WithoutAccountAdminAuthority() throws Exception {
        mockMvc.perform(head("/accounts").with(jwt().authorities(() -> "account-reader")))
                .andExpect(status().isForbidden());
    }

    @Test
    void getAccountsReturns200WithAccountAdminAuthority() throws Exception {
        mockMvc.perform(get("/accounts").with(jwt().authorities(() -> "account-admin")))
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
    void postAccountsBindsOwnerIdToTheCallersSubject() throws Exception {
        UUID subject = UUID.randomUUID();
        when(accountService.createAccount(any(), any(), any()))
                .thenReturn(new Account(subject, "Ada", new BigDecimal("10.00")));

        mockMvc.perform(post("/accounts")
                        .contentType("application/json")
                        .content("{\"ownerName\":\"Ada\",\"initialBalance\":10.00}")
                        .with(jwt().jwt(builder -> builder.subject(subject.toString()))
                                .authorities(() -> "account-editor")))
                .andExpect(status().isCreated());

        verify(accountService).createAccount(eq(subject), eq("Ada"), eq(new BigDecimal("10.00")));
    }

    @Test
    void actuatorHealthNeedsNoToken() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    // GET /accounts/{id} -- owner-gated as of Phase 7b.

    @Test
    void getAccountReturns401WithNoToken() throws Exception {
        mockMvc.perform(get("/accounts/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getAccountReturns403WithoutAccountReaderAuthority() throws Exception {
        mockMvc.perform(get("/accounts/" + UUID.randomUUID()).with(jwt().authorities(() -> "account-editor")))
                .andExpect(status().isForbidden());
    }

    @Test
    void headAccountReturns403WithoutAccountReaderAuthority() throws Exception {
        mockMvc.perform(head("/accounts/" + UUID.randomUUID()).with(jwt().authorities(() -> "account-editor")))
                .andExpect(status().isForbidden());
    }

    @Test
    void getAccountPassesTheCallersSubjectToTheService() throws Exception {
        UUID id = UUID.randomUUID();
        UUID subject = UUID.randomUUID();
        when(accountService.getAccount(id, subject)).thenReturn(new Account(subject, "Ada", new BigDecimal("60.00")));

        mockMvc.perform(get("/accounts/" + id)
                        .with(jwt().jwt(builder -> builder.subject(subject.toString()))
                                .authorities(() -> "account-reader")))
                .andExpect(status().isOk());

        verify(accountService).getAccount(id, subject);
    }

    @Test
    void getAccountReturns404WhenTheServiceRejectsOwnership() throws Exception {
        // AccountService.getAccount reuses AccountNotFoundException for a non-owning caller --
        // see docs/phase-7b-account-ownership-authorization.md's Design Decisions. This proves
        // that 404, however it originates, reaches the caller correctly through the real filter
        // chain and ApiExceptionHandler.
        UUID id = UUID.randomUUID();
        when(accountService.getAccount(eq(id), any())).thenThrow(new AccountNotFoundException(id));

        mockMvc.perform(get("/accounts/" + id)
                        .with(jwt().jwt(builder -> builder.subject(UUID.randomUUID().toString()))
                                .authorities(() -> "account-reader")))
                .andExpect(status().isNotFound());
    }

    // GET /accounts/exists/{id} -- existence only, account-reader, no ownership.

    @Test
    void accountExistsReturns401WithNoToken() throws Exception {
        mockMvc.perform(get("/accounts/exists/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void accountExistsReturns403WithoutAccountReaderAuthority() throws Exception {
        mockMvc.perform(get("/accounts/exists/" + UUID.randomUUID()).with(jwt().authorities(() -> "account-editor")))
                .andExpect(status().isForbidden());
    }

    @Test
    void headAccountExistsReturns403WithoutAccountReaderAuthority() throws Exception {
        mockMvc.perform(head("/accounts/exists/" + UUID.randomUUID()).with(jwt().authorities(() -> "account-editor")))
                .andExpect(status().isForbidden());
    }

    @Test
    void accountExistsReturns200WithAccountReaderAuthorityRegardlessOfOwnership() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(get("/accounts/exists/" + id).with(jwt().authorities(() -> "account-reader")))
                .andExpect(status().isOk());

        verify(accountService).requireAccountExists(id);
    }

    @Test
    void accountExistsReturns404WhenMissing() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new AccountNotFoundException(id)).when(accountService).requireAccountExists(id);

        mockMvc.perform(get("/accounts/exists/" + id).with(jwt().authorities(() -> "account-reader")))
                .andExpect(status().isNotFound());
    }

    // Money-moving endpoints -- found missing in code review (a wrong or dropped authority
    // check here would have shipped with the rest of this test class green).

    @Test
    void debitReturns401WithNoToken() throws Exception {
        mockMvc.perform(post("/accounts/" + UUID.randomUUID() + "/debit")
                        .contentType("application/json")
                        .header("Idempotency-Key", "test-key")
                        .content("{\"amount\":10.00}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void debitReturns403WithoutAccountEditorAuthority() throws Exception {
        mockMvc.perform(post("/accounts/" + UUID.randomUUID() + "/debit")
                        .contentType("application/json")
                        .header("Idempotency-Key", "test-key")
                        .content("{\"amount\":10.00}")
                        .with(jwt().authorities(() -> "account-reader")))
                .andExpect(status().isForbidden());
    }

    @Test
    void debitReturns200WithAccountEditorAuthority() throws Exception {
        UUID subject = UUID.randomUUID();
        when(accountService.debit(any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new Account(subject, "Ada", new BigDecimal("60.00")));

        mockMvc.perform(post("/accounts/" + UUID.randomUUID() + "/debit")
                        .contentType("application/json")
                        .header("Idempotency-Key", "test-key")
                        .content("{\"amount\":10.00}")
                        .with(jwt().jwt(builder -> builder.subject(subject.toString()))
                                .authorities(() -> "account-editor")))
                .andExpect(status().isOk());
    }

    @Test
    void debitPassesTheCallersSubjectAndServiceCallerFalseForAnOrdinaryCustomer() throws Exception {
        UUID id = UUID.randomUUID();
        UUID subject = UUID.randomUUID();
        when(accountService.debit(any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new Account(subject, "Ada", new BigDecimal("60.00")));

        mockMvc.perform(post("/accounts/" + id + "/debit")
                        .contentType("application/json")
                        .header("Idempotency-Key", "test-key")
                        .content("{\"amount\":10.00}")
                        .with(jwt().jwt(builder -> builder.subject(subject.toString()))
                                .authorities(() -> "account-editor")))
                .andExpect(status().isOk());

        verify(accountService).debit(eq(id), eq(new BigDecimal("10.00")), eq("test-key"), eq(subject), eq(false));
    }

    @Test
    void debitPassesServiceCallerTrueForTheTransferServiceClient() throws Exception {
        // The "azp" claim is Keycloak's standard "authorized party" claim on a client-credentials
        // token -- see docs/phase-7b-account-ownership-authorization.md's Design Decisions for
        // why transfer-service's machine identity is exempted from the ownership check.
        UUID id = UUID.randomUUID();
        UUID subject = UUID.randomUUID();
        when(accountService.debit(any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new Account(subject, "Ada", new BigDecimal("60.00")));

        mockMvc.perform(post("/accounts/" + id + "/debit")
                        .contentType("application/json")
                        .header("Idempotency-Key", "test-key")
                        .content("{\"amount\":10.00}")
                        .with(jwt().jwt(builder -> builder.subject(subject.toString()).claim("azp", "transfer-service"))
                                .authorities(() -> "account-editor")))
                .andExpect(status().isOk());

        verify(accountService).debit(eq(id), eq(new BigDecimal("10.00")), eq("test-key"), eq(subject), eq(true));
    }

    @Test
    void debitReturns404WhenTheServiceRejectsOwnership() throws Exception {
        UUID id = UUID.randomUUID();
        when(accountService.debit(eq(id), any(), any(), any(), eq(false)))
                .thenThrow(new AccountNotFoundException(id));

        mockMvc.perform(post("/accounts/" + id + "/debit")
                        .contentType("application/json")
                        .header("Idempotency-Key", "test-key")
                        .content("{\"amount\":10.00}")
                        .with(jwt().jwt(builder -> builder.subject(UUID.randomUUID().toString()))
                                .authorities(() -> "account-editor")))
                .andExpect(status().isNotFound());
    }

    @Test
    void creditReturns401WithNoToken() throws Exception {
        mockMvc.perform(post("/accounts/" + UUID.randomUUID() + "/credit")
                        .contentType("application/json")
                        .header("Idempotency-Key", "test-key")
                        .content("{\"amount\":10.00}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void creditReturns403ForACustomerHoldingAccountEditor() throws Exception {
        // The customer composite carries account-editor. Before credit required its own
        // authority, any customer could credit any account directly on port 8081 -- creating
        // money with no matching debit (docs/open-items.md #2, now closed).
        mockMvc.perform(post("/accounts/" + UUID.randomUUID() + "/credit")
                        .contentType("application/json")
                        .header("Idempotency-Key", "test-key")
                        .content("{\"amount\":10.00}")
                        .with(jwt().authorities(() -> "account-editor", () -> "account-reader")))
                .andExpect(status().isForbidden());
    }

    @Test
    void creditReturns200WithAccountCrediterAuthorityRegardlessOfOwnership() throws Exception {
        // account-crediter is held only by transfer-service's machine identity. credit has no
        // ownership check -- see docs/phase-7b-account-ownership-authorization.md's Design
        // Decisions for why it can't (a transfer credits someone else's account).
        UUID subject = UUID.randomUUID();
        when(accountService.credit(any(), any(), any())).thenReturn(new Account(subject, "Ada", new BigDecimal("60.00")));

        mockMvc.perform(post("/accounts/" + UUID.randomUUID() + "/credit")
                        .contentType("application/json")
                        .header("Idempotency-Key", "test-key")
                        .content("{\"amount\":10.00}")
                        .with(jwt().authorities(() -> "account-crediter")))
                .andExpect(status().isOk());
    }
}
