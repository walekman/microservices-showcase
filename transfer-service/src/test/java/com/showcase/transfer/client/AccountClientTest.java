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
    void getAccountReturnsTheBalance() {
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"id":"%s","ownerName":"Ada Lovelace","balance":100.00,"createdAt":"2026-09-10T12:00:00Z"}
                        """.formatted(ACCOUNT_ID), MediaType.APPLICATION_JSON));

        AccountView account = accountClient.getAccount(ACCOUNT_ID);

        assertThat(account.id()).isEqualTo(ACCOUNT_ID);
        assertThat(account.balance()).isEqualByComparingTo("100.00");
        server.verify();
    }

    @Test
    void getAccountThrowsRejectedWithAccountCodeOn404() {
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID))
                .andRespond(problem(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account not found: " + ACCOUNT_ID));

        assertThatThrownBy(() -> accountClient.getAccount(ACCOUNT_ID))
                .isInstanceOf(AccountRejectedException.class)
                .satisfies(thrown -> {
                    assertThat(((AccountRejectedException) thrown).getCode()).isEqualTo("ACCOUNT_NOT_FOUND");
                    assertThat(((AccountRejectedException) thrown).getDetail())
                            .isEqualTo("Account not found: " + ACCOUNT_ID);
                });
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
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.TEXT_HTML)
                        .body("<html>gateway says no</html>"));

        assertThatThrownBy(() -> accountClient.getAccount(ACCOUNT_ID))
                .isInstanceOf(AccountRejectedException.class)
                .satisfies(thrown -> assertThat(((AccountRejectedException) thrown).getCode())
                        .isEqualTo("UNKNOWN"));
        server.verify();
    }

    @Test
    void getAccountThrowsUnavailableWhenTheBodyIsEmpty() {
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID))
                .andRespond(withSuccess());

        assertThatThrownBy(() -> accountClient.getAccount(ACCOUNT_ID))
                .isInstanceOf(AccountServiceUnavailableException.class);
        server.verify();
    }

    @Test
    void getAccountThrowsUnavailableWhenA2xxBodyIsNotReadable() {
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID))
                .andRespond(withSuccess("<html>proxy interstitial</html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> accountClient.getAccount(ACCOUNT_ID))
                .isInstanceOf(AccountServiceUnavailableException.class);
        server.verify();
    }

    @Test
    void throwsRejectedWithUnknownCodeWhenTheErrorHasNoBodyAtAll() {
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> accountClient.getAccount(ACCOUNT_ID))
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
    void getAccountThrowsUnavailableOn3xxRedirect() {
        server.expect(requestTo(BASE_URL + "/accounts/" + ACCOUNT_ID))
                .andRespond(withStatus(HttpStatus.FOUND));

        assertThatThrownBy(() -> accountClient.getAccount(ACCOUNT_ID))
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
