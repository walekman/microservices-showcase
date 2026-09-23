package com.showcase.transfer.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
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
 *
 * <p>Wrapped in Resilience4j CircuitBreaker + Retry (see application.yml's
 * resilience4j.* "accountService" instance): {@link AccountRejectedException} is an
 * ignored exception (never retried, never trips the breaker — a business rejection
 * is not infrastructure trouble), {@link AccountServiceUnavailableException} is
 * retried. When the circuit is open, Resilience4j's own {@code CallNotPermittedException}
 * is thrown by the AOP proxy BEFORE the method body runs, so it cannot be caught inside
 * these methods — it is handled by the fallback methods below instead, which remap it
 * onto {@link AccountServiceUnavailableException} so an open circuit looks, correctly,
 * exactly like Account being unavailable to every caller of this class.
 *
 * <p><b>Verified live, not just assumed:</b> once a {@code fallbackMethod} is specified,
 * Resilience4j's Spring AOP routes every exception the decorated method throws through it
 * — including ones listed in {@code ignoreExceptions} — not only retried-and-exhausted or
 * circuit-open failures. Without the explicit passthrough in the fallback methods below, a
 * definitively rejected request (e.g. {@code ACCOUNT_NOT_FOUND}) was silently
 * miscategorized as {@link AccountServiceUnavailableException}. Unit tests that mock this
 * class entirely (as {@code CompensationSchedulerTest} does) cannot catch this — mocking
 * bypasses the AOP proxy, so the fallback method is never exercised. Only running the real,
 * annotated bean surfaced it.
 */
public class AccountClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public AccountClient(RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Confirms an account exists, nothing more -- backs the saga's pre-validate step for both
     * legs, including the destination, which the initiating caller never owns. Deliberately
     * hits the ownership-free {@code GET /accounts/exists/{id}} rather than the full
     * owner-gated {@code GET /accounts/{id}}: the latter would 404 for every transfer to a
     * different customer's account. See docs/phase-7b-account-ownership-authorization.md.
     */
    @CircuitBreaker(name = "accountService")
    @Retry(name = "accountService", fallbackMethod = "accountExistsFallback")
    public void accountExists(UUID accountId) {
        call(() -> restClient.get()
                .uri("/accounts/exists/{id}", accountId)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::rejected)
                .onStatus(status -> !status.is2xxSuccessful(), this::unavailable)
                .toBodilessEntity());
    }

    /**
     * Whether the CURRENT caller (whatever token this thread relays -- see
     * AuthorizationPropagatingInterceptor) owns the given account, via Account's owner-gated
     * {@code GET /accounts/{id}}. Used only by TransferService.getTransfer's destination-owner
     * read check -- see docs/phase-7b-account-ownership-authorization.md's Design Decisions for
     * why this is a live per-read check rather than something Transfer stores.
     *
     * <p>A 404 (owner mismatch or genuinely missing, indistinguishable by design) resolves to
     * {@code false} rather than propagating -- that is a normal, expected outcome of this check,
     * not a rejection. Anything else (Account unavailable) still propagates, since "unknown" is
     * not the same answer as "not yours".
     */
    @CircuitBreaker(name = "accountService")
    @Retry(name = "accountService", fallbackMethod = "isOwnedByCallerFallback")
    public boolean isOwnedByCaller(UUID accountId) {
        try {
            call(() -> restClient.get()
                    .uri("/accounts/{id}", accountId)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, this::rejected)
                    .onStatus(status -> !status.is2xxSuccessful(), this::unavailable)
                    .toBodilessEntity());
            return true;
        } catch (AccountRejectedException rejected) {
            // Only Account's own reused-404 ownership-denial code resolves to false. Anything
            // else (e.g. an "UNKNOWN" code from a malformed/proxy-mangled error body) is a
            // genuine anomaly, not a normal "not yours" answer, and must not be silently read
            // as a denial -- rethrown instead. Found in code review.
            if (!"ACCOUNT_NOT_FOUND".equals(rejected.getCode())) {
                throw rejected;
            }
            return false;
        }
    }

    @CircuitBreaker(name = "accountService")
    @Retry(name = "accountService", fallbackMethod = "debitCreditFallback")
    public void debit(UUID accountId, BigDecimal amount, String idempotencyKey) {
        post(accountId, amount, "debit", idempotencyKey, false);
    }

    @CircuitBreaker(name = "accountService")
    @Retry(name = "accountService", fallbackMethod = "debitCreditFallback")
    public void credit(UUID accountId, BigDecimal amount, String idempotencyKey) {
        // Always as transfer-service itself: Account requires account-crediter for credit, which
        // no customer holds. See AuthorizationPropagatingInterceptor.USE_SERVICE_IDENTITY.
        post(accountId, amount, "credit", idempotencyKey, true);
    }

    private void post(UUID accountId, BigDecimal amount, String operation, String idempotencyKey,
                      boolean serviceIdentity) {
        call(() -> restClient.post()
                .uri("/accounts/{id}/{operation}", accountId, operation)
                .attribute(AuthorizationPropagatingInterceptor.USE_SERVICE_IDENTITY, serviceIdentity)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotencyKey)
                .body(Map.of("amount", amount))
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::rejected)
                // Treat anything that is not 2xx as unavailable: 5xx and 3xx (redirects) are
                // both unconfirmed outcomes. If a 3xx fell through and was treated as success,
                // a redirect in the debit leg would silently create money (credit committed,
                // debit unconfirmed).
                .onStatus(status -> !status.is2xxSuccessful(), this::unavailable)
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

    private void rejected(HttpRequest request, ClientHttpResponse response) throws IOException {
        // A 401/403 is Spring Security denying the request before it ever reaches Account's
        // own RFC 7807 handler -- body is empty, no content-type. Before this project added
        // JWT auth, Account could never return either status, so this check didn't exist and
        // readProblem() below silently turned an auth/config failure into "UNKNOWN", which
        // rejected() would then treat as a definitive business rejection (see the
        // CONCURRENT_MODIFICATION comment below for why that's dangerous specifically for
        // CompensationScheduler: it would stamp a transfer FAILED/COMPENSATED on the strength
        // of a credential problem, not a real business answer, even if the debit/credit it's
        // reasoning about actually landed). Checked on the status code directly, not the
        // body, since there is no body to parse.
        int status = response.getStatusCode().value();
        if (status == 401 || status == 403) {
            throw new AccountServiceUnavailableException(
                    "Account Service rejected this caller's credentials (HTTP " + status + ")");
        }
        AccountProblem problem = readProblem(response);
        if ("CONCURRENT_MODIFICATION".equals(problem.code())) {
            // Account's own optimistic-lock conflict (its @Version check losing a race) is
            // genuinely transient, not a business rejection -- unlike every other 4xx this
            // method handles. Surfacing it as AccountRejectedException would make the live
            // saga abort permanently and, worse, make CompensationScheduler read a routine
            // version conflict during a sweep as a definitive rejection and reverse a
            // transfer that never actually failed (see docs/roadmap.md's Phase 3 review
            // findings). AccountServiceUnavailableException instead lets Resilience4j's
            // @Retry retry it live, and lets the compensator's sweep retry it next pass.
            throw new AccountServiceUnavailableException(
                    "Account Service reported a transient conflict: " + problem.detail());
        }
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

    /**
     * Invoked by Resilience4j instead of accountExists's body -- not only once retries are
     * exhausted or the circuit is open, but for every exception the body can throw,
     * {@link AccountRejectedException} included (see the class javadoc). Rethrown
     * unchanged so a business rejection still reaches the caller as a rejection.
     */
    private void accountExistsFallback(UUID accountId, Throwable t) {
        throw rethrow(t);
    }

    /**
     * Same reasoning as accountExistsFallback above, for isOwnedByCaller. Only reached once
     * retries/circuit-breaker give up -- the AccountRejectedException "not owned" case is
     * handled inside isOwnedByCaller's own body and never escapes to trigger this.
     */
    private boolean isOwnedByCallerFallback(UUID accountId, Throwable t) {
        throw rethrow(t);
    }

    /** Shared fallback for debit and credit — both have the same (UUID, BigDecimal, String) shape. */
    private void debitCreditFallback(UUID accountId, BigDecimal amount, String idempotencyKey, Throwable t) {
        throw rethrow(t);
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof AccountRejectedException rejected) {
            return rejected;
        }
        if (t instanceof AccountServiceUnavailableException already) {
            return already;
        }
        return new AccountServiceUnavailableException("Account Service call failed: " + t.getMessage(), t);
    }
}
