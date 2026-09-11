package com.showcase.transfer.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Calls Account Service over blocking HTTP. Every outcome collapses onto two
 * exceptions so the saga can branch on "rejected" versus "broken" without ever
 * seeing an HTTP status.
 */
public class AccountClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public AccountClient(RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    public AccountView getAccount(UUID accountId) {
        // A 204, or a 200 with Content-Length: 0, makes the message converter return null.
        // Without this guard the saga NPEs on account.balance() instead of branching.
        AccountView account = call(() -> restClient.get()
                .uri("/accounts/{id}", accountId)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::rejected)
                .onStatus(HttpStatusCode::is5xxServerError, this::unavailable)
                .body(AccountView.class));
        if (account == null) {
            throw new AccountServiceUnavailableException(
                    "Account Service returned an empty body for " + accountId);
        }
        return account;
    }

    public void debit(UUID accountId, BigDecimal amount) {
        post(accountId, amount, "debit");
    }

    public void credit(UUID accountId, BigDecimal amount) {
        post(accountId, amount, "credit");
    }

    private void post(UUID accountId, BigDecimal amount, String operation) {
        call(() -> restClient.post()
                .uri("/accounts/{id}/{operation}", accountId, operation)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("amount", amount))
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::rejected)
                .onStatus(HttpStatusCode::is5xxServerError, this::unavailable)
                .toBodilessEntity());
    }

    /**
     * Connection refused, DNS failure and read timeout all surface as
     * ResourceAccessException rather than a status code, so they are translated here
     * instead of in an onStatus handler.
     */
    private <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientException ex) {
            // Deliberately the broad superclass, not just ResourceAccessException.
            // ResourceAccessException covers connect-refused/DNS/read-timeout, but a 2xx
            // carrying an unreadable body throws UnknownContentTypeException (proxy
            // interstitial HTML) or a plain RestClientException (malformed JSON) instead.
            // Catching only the narrow type lets those escape BOTH domain exceptions and
            // reach the saga unhandled -- which defeats this class entirely. The two
            // domain exceptions do not extend RestClientException, so they still pass
            // through untouched.
            throw new AccountServiceUnavailableException("Account Service call failed: " + ex.getMessage(), ex);
        }
    }

    private void rejected(HttpRequest request, ClientHttpResponse response) {
        AccountProblem problem = readProblem(response);
        throw new AccountRejectedException(problem.code(), problem.detail());
    }

    private void unavailable(HttpRequest request, ClientHttpResponse response) throws IOException {
        throw new AccountServiceUnavailableException(
                "Account Service returned " + response.getStatusCode().value());
    }

    /**
     * An error body that is not a well-formed problem document (an HTML page from a
     * proxy, an empty body) must still produce a domain exception, never a parse error.
     */
    private AccountProblem readProblem(ClientHttpResponse response) {
        try {
            AccountProblem problem = objectMapper.readValue(response.getBody(), AccountProblem.class);
            if (problem == null || problem.code() == null) {
                return new AccountProblem("UNKNOWN", "Account Service returned an unrecognised error body");
            }
            // Account may omit "detail"; without this the exception message reads "...: null".
            return (problem.detail() != null) ? problem : new AccountProblem(problem.code(), "");
        } catch (Exception ex) {
            return new AccountProblem("UNKNOWN", "Account Service returned an unreadable error body");
        }
    }
}
