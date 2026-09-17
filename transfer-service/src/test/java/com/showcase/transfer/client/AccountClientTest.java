package com.showcase.transfer.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AccountClientTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String BASE_URL = "http://account-service:8081";

    private MockRestServiceServer server;
    private AccountClient accountClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        accountClient = new AccountClient(builder.build(), new ObjectMapper());
    }

    @Test
    void accountExistsSucceedsWhenTheAccountExists() {
        server.expect(requestTo(BASE_URL + "/accounts/exists/" + ACCOUNT_ID))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.OK));

        accountClient.accountExists(ACCOUNT_ID);

        server.verify();
    }

    @Test
    void accountExistsThrowsRejectedWithAccountCodeOn404() {
        server.expect(requestTo(BASE_URL + "/accounts/exists/" + ACCOUNT_ID))
                .andRespond(problem(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account not found: " + ACCOUNT_ID));

        assertThatThrownBy(() -> accountClient.accountExists(ACCOUNT_ID))
                .isInstanceOf(AccountRejectedException.class)
                .satisfies(thrown -> {
                    assertThat(((AccountRejectedException) thrown).getCode()).isEqualTo("ACCOUNT_NOT_FOUND");
                    assertThat(((AccountRejectedException) thrown).getDetail())
                            .isEqualTo("Account not found: " + ACCOUNT_ID);
                });
        server.verify();
    }

    // isOwnedByCaller -- backs TransferService.getTransfer's destination-owner read check
    // (see docs/phase-7b-account-ownership-authorization.md). Hits the owner-gated
    // GET /accounts/{id}, unlike accountExists above.

    @Test
    void isOwnedByCallerReturnsTrueOn200() {
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.OK));

        assertThat(accountClient.isOwnedByCaller(ACCOUNT_ID)).isTrue();
        server.verify();
    }

    @Test
    void isOwnedByCallerReturnsFalseOnAccountNotFound() {
        // 404/ACCOUNT_NOT_FOUND covers both "genuinely missing" and "not yours" -- Account
        // deliberately does not distinguish the two. Either way, this is a normal, expected
        // outcome of the check, not a propagated rejection.
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID))
                .andRespond(problem(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account not found: " + ACCOUNT_ID));

        assertThat(accountClient.isOwnedByCaller(ACCOUNT_ID)).isFalse();
        server.verify();
    }

    @Test
    void isOwnedByCallerRethrowsARejectionWithAnUnrecognisedCode() {
        // A malformed/proxy-mangled error body reads as AccountRejectedException("UNKNOWN", ...)
        // (see readProblem()) -- that must not be silently read as "not owned" the way a genuine
        // ACCOUNT_NOT_FOUND is. Found in code review.
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.TEXT_HTML)
                        .body("<html>gateway says no</html>"));

        assertThatThrownBy(() -> accountClient.isOwnedByCaller(ACCOUNT_ID))
                .isInstanceOf(AccountRejectedException.class)
                .satisfies(thrown -> assertThat(((AccountRejectedException) thrown).getCode())
                        .isEqualTo("UNKNOWN"));
        server.verify();
    }

    @Test
    void isOwnedByCallerPropagatesUnavailableOnServerError() {
        // Unlike ACCOUNT_NOT_FOUND above, "Account is unreachable" must not resolve to false --
        // that would answer "not yours" when the real answer is "unknown".
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> accountClient.isOwnedByCaller(ACCOUNT_ID))
                .isInstanceOf(AccountServiceUnavailableException.class);
        server.verify();
    }

    @Test
    void debitPostsTheAmountAndIdempotencyKeyAndSucceeds() {
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID + "/debit"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(jsonPath("$.amount").value(40.00))
                .andExpect(header("Idempotency-Key", "transfer-1:debit"))
                .andRespond(withSuccess("""
                        {"id":"%s","ownerName":"Ada Lovelace","balance":60.00,"createdAt":"2026-09-10T12:00:00Z"}
                        """.formatted(ACCOUNT_ID), MediaType.APPLICATION_JSON));

        accountClient.debit(ACCOUNT_ID, new BigDecimal("40.00"), "transfer-1:debit");

        server.verify();
    }

    @Test
    void debitThrowsRejectedOnInsufficientFunds() {
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID + "/debit"))
                .andRespond(problem(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS", "not enough money"));

        assertThatThrownBy(() -> accountClient.debit(ACCOUNT_ID, new BigDecimal("40.00"), "transfer-1:debit"))
                .isInstanceOf(AccountRejectedException.class)
                .satisfies(thrown -> assertThat(((AccountRejectedException) thrown).getCode())
                        .isEqualTo("INSUFFICIENT_FUNDS"));
        server.verify();
    }

    @Test
    void debitThrowsUnavailableNotRejectedOnConcurrentModification() {
        // CONCURRENT_MODIFICATION is Account's own optimistic-lock conflict -- genuinely
        // transient, not a business rejection. Callers (the live saga's Resilience4j @Retry,
        // and CompensationScheduler's reconciliation) must be able to retry it, which only
        // happens if this surfaces as AccountServiceUnavailableException, never
        // AccountRejectedException. See docs/roadmap.md's Phase 3 review findings.
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID + "/debit"))
                .andRespond(problem(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION", "Account was modified concurrently, please retry"));

        assertThatThrownBy(() -> accountClient.debit(ACCOUNT_ID, new BigDecimal("40.00"), "transfer-1:debit"))
                .isInstanceOf(AccountServiceUnavailableException.class)
                .isNotInstanceOf(AccountRejectedException.class);
        server.verify();
    }

    @Test
    void debitThrowsUnavailableNotRejectedOn401() {
        // A 401/403 is Spring Security denying the request before it ever reaches Account's
        // own RFC 7807 handler -- empty body, no content-type -- so without this check it
        // would fall through readProblem() into "UNKNOWN" and be treated as a definitive
        // rejection, exactly like the CONCURRENT_MODIFICATION case above. CompensationScheduler
        // would then stamp a transfer FAILED/COMPENSATED on the strength of a credential
        // problem (e.g. a Keycloak restart rotating signing keys while a cached token is still
        // in use), not a real business answer -- even if the debit it's reasoning about
        // actually landed. Found in code review, not written test-first.
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID + "/debit"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> accountClient.debit(ACCOUNT_ID, new BigDecimal("40.00"), "transfer-1:debit"))
                .isInstanceOf(AccountServiceUnavailableException.class)
                .isNotInstanceOf(AccountRejectedException.class);
        server.verify();
    }

    @Test
    void debitThrowsUnavailableNotRejectedOn403() {
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID + "/debit"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> accountClient.debit(ACCOUNT_ID, new BigDecimal("40.00"), "transfer-1:debit"))
                .isInstanceOf(AccountServiceUnavailableException.class)
                .isNotInstanceOf(AccountRejectedException.class);
        server.verify();
    }

    @Test
    void debitStillThrowsRejectedOnIdempotencyKeyConflict() {
        // A DIFFERENT 409 code from the same status: reusing a key against a different
        // account/amount/type is a genuine, permanent rejection, not a transient race --
        // it must keep failing AccountRejectedException, not get swept up by the
        // CONCURRENT_MODIFICATION special-case above.
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID + "/debit"))
                .andRespond(problem(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT", "Idempotency key already used with different parameters"));

        assertThatThrownBy(() -> accountClient.debit(ACCOUNT_ID, new BigDecimal("40.00"), "transfer-1:debit"))
                .isInstanceOf(AccountRejectedException.class)
                .satisfies(thrown -> assertThat(((AccountRejectedException) thrown).getCode())
                        .isEqualTo("IDEMPOTENCY_KEY_CONFLICT"));
        server.verify();
    }

    @Test
    void creditPostsTheIdempotencyKey() {
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID + "/credit"))
                .andExpect(header("Idempotency-Key", "transfer-1:credit"))
                .andRespond(withSuccess("""
                        {"id":"%s","ownerName":"Ada Lovelace","balance":140.00,"createdAt":"2026-09-10T12:00:00Z"}
                        """.formatted(ACCOUNT_ID), MediaType.APPLICATION_JSON));

        accountClient.credit(ACCOUNT_ID, new BigDecimal("40.00"), "transfer-1:credit");

        server.verify();
    }

    @Test
    void throwsUnavailableOnServerError() {
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID + "/credit"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> accountClient.credit(ACCOUNT_ID, new BigDecimal("40.00"), "transfer-1:credit"))
                .isInstanceOf(AccountServiceUnavailableException.class);
        server.verify();
    }

    @Test
    void throwsUnavailableWhenTheConnectionFails() {
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID + "/credit"))
                .andRespond(withException(new java.net.ConnectException("connection refused")));

        assertThatThrownBy(() -> accountClient.credit(ACCOUNT_ID, new BigDecimal("40.00"), "transfer-1:credit"))
                .isInstanceOf(AccountServiceUnavailableException.class);
        server.verify();
    }

    @Test
    void throwsRejectedWithUnknownCodeWhenTheErrorBodyIsNotAProblem() {
        server.expect(requestTo(BASE_URL + "/accounts/exists/" + ACCOUNT_ID))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.TEXT_HTML)
                        .body("<html>gateway says no</html>"));

        assertThatThrownBy(() -> accountClient.accountExists(ACCOUNT_ID))
                .isInstanceOf(AccountRejectedException.class)
                .satisfies(thrown -> assertThat(((AccountRejectedException) thrown).getCode())
                        .isEqualTo("UNKNOWN"));
        server.verify();
    }

    @Test
    void throwsRejectedWithUnknownCodeWhenTheErrorHasNoBodyAtAll() {
        server.expect(requestTo(BASE_URL + "/accounts/exists/" + ACCOUNT_ID))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> accountClient.accountExists(ACCOUNT_ID))
                .isInstanceOf(AccountRejectedException.class)
                .satisfies(thrown -> assertThat(((AccountRejectedException) thrown).getCode())
                        .isEqualTo("UNKNOWN"));
        server.verify();
    }

    @Test
    void debitThrowsUnavailableOn3xxRedirect() {
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID + "/debit"))
                .andRespond(withStatus(HttpStatus.FOUND));

        assertThatThrownBy(() -> accountClient.debit(ACCOUNT_ID, new BigDecimal("40.00"), "transfer-1:debit"))
                .isInstanceOf(AccountServiceUnavailableException.class);
        server.verify();
    }

    @Test
    void accountExistsThrowsUnavailableOn3xxRedirect() {
        server.expect(requestTo(BASE_URL + "/accounts/exists/" + ACCOUNT_ID))
                .andRespond(withStatus(HttpStatus.FOUND));

        assertThatThrownBy(() -> accountClient.accountExists(ACCOUNT_ID))
                .isInstanceOf(AccountServiceUnavailableException.class);
        server.verify();
    }

    private static org.springframework.test.web.client.ResponseCreator problem(
            HttpStatus status, String code, String detail) {
        return withStatus(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body("""
                        {"type":"https://showcase.example/errors/x","title":"t","status":%d,
                         "detail":"%s","code":"%s","timestamp":"2026-09-10T12:00:00Z"}
                        """.formatted(status.value(), detail, code));
    }
}
