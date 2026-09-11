package com.showcase.transfer.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

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
        return call(() -> restClient.get()
                .uri("/accounts/{id}", accountId)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::rejected)
                .onStatus(HttpStatusCode::is5xxServerError, this::unavailable)
                .body(AccountView.class));
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
    private <T> T call(java.util.function.Supplier<T> request) {
        try {
            return request.get();
        } catch (ResourceAccessException ex) {
            throw new AccountServiceUnavailableException("Account Service is unreachable: " + ex.getMessage(), ex);
        }
    }

    private void rejected(HttpRequest request, ClientHttpResponse response) throws IOException {
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
            return problem;
        } catch (Exception ex) {
            return new AccountProblem("UNKNOWN", "Account Service returned an unreadable error body");
        }
    }
}
