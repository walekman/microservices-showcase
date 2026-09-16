package com.showcase.transfer.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Calls Fraud Service's account-blocklist check over blocking HTTP. Same two-exception
 * shape as {@link AccountClient}: {@link FraudRejectedException} is an ignored exception
 * (never retried, never trips the breaker -- a block is not infrastructure trouble),
 * {@link FraudServiceUnavailableException} is retried. See application.yml's
 * resilience4j.*.instances.fraudService.
 *
 * <p>Same "verified live, not just assumed" caution as {@link AccountClient}'s javadoc:
 * once a fallbackMethod is specified, Resilience4j routes every exception the decorated
 * method throws through it, including ones listed in ignoreExceptions -- see
 * {@code checkFallback} below and {@code FraudClientFallbackIT}.
 */
public class FraudClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public FraudClient(RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    @CircuitBreaker(name = "fraudService")
    @Retry(name = "fraudService", fallbackMethod = "checkFallback")
    public void check(UUID accountId) {
        call(() -> restClient.get()
                .uri("/fraud-check?accountId={id}", accountId)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::rejected)
                .onStatus(status -> !status.is2xxSuccessful(), this::unavailable)
                .toBodilessEntity());
    }

    private <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientException ex) {
            throw new FraudServiceUnavailableException("Fraud Service call failed: " + ex.getMessage(), ex);
        }
    }

    private void rejected(HttpRequest request, ClientHttpResponse response) {
        FraudProblem problem = readProblem(response);
        if (!"ACCOUNT_BLOCKED".equals(problem.code())) {
            // Any 4xx that isn't a definitive block -- an unrecognised code, no code at all,
            // a misconfigured fraud-service.base-url producing a 404, a gateway/proxy
            // interstitial, or Fraud Service's own defensive 400s (VALIDATION_FAILED/
            // MALFORMED_REQUEST) -- is NOT a business verdict. Treating it as one would let
            // a misconfiguration or a future auth hop (Phase 6's Gateway) silently reverse a
            // good transfer or terminate a transfer's recovery on a wrong answer.
            throw new FraudServiceUnavailableException(
                    "Fraud Service returned an unrecognised rejection [" + problem.code() + "]: " + problem.detail());
        }
        throw new FraudRejectedException(problem.detail());
    }

    private void unavailable(HttpRequest request, ClientHttpResponse response) throws IOException {
        throw new FraudServiceUnavailableException("Fraud Service returned " + response.getStatusCode().value());
    }

    /**
     * An error body that is not a well-formed problem document (an HTML page from a proxy,
     * an empty body) must still produce a domain exception, never a parse error -- mirrors
     * AccountClient.readProblem() exactly.
     */
    private FraudProblem readProblem(ClientHttpResponse response) {
        try {
            FraudProblem problem = objectMapper.readValue(response.getBody(), FraudProblem.class);
            if (problem == null || problem.code() == null) {
                return new FraudProblem("UNKNOWN", "Fraud Service returned an unrecognised error body");
            }
            return (problem.detail() != null) ? problem : new FraudProblem(problem.code(), "");
        } catch (Exception ex) {
            return new FraudProblem("UNKNOWN", "Fraud Service returned an unreadable error body");
        }
    }

    /** Invoked for every exception check() throws, FraudRejectedException included -- see class javadoc. */
    private void checkFallback(UUID accountId, Throwable t) {
        throw rethrow(t);
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof FraudRejectedException rejected) {
            return rejected;
        }
        if (t instanceof FraudServiceUnavailableException already) {
            return already;
        }
        return new FraudServiceUnavailableException("Fraud Service call failed: " + t.getMessage(), t);
    }
}
