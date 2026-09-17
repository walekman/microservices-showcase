package com.showcase.account.api;

import com.showcase.account.support.TestSecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(TestSecurityConfig.class)
class AccountControllerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void authenticateAsCustomer() {
        TestSecurityConfig.authenticateAsCustomer(restTemplate);
    }

    @Test
    void createsAndFetchesAnAccount() {
        ResponseEntity<AccountResponse> createResponse = restTemplate.postForEntity(
                "/accounts", new CreateAccountRequest("Ada Lovelace", new BigDecimal("100.00")), AccountResponse.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID id = createResponse.getBody().id();

        ResponseEntity<AccountResponse> getResponse = restTemplate.getForEntity("/accounts/" + id, AccountResponse.class);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody().balance()).isEqualByComparingTo("100.00");
    }

    @Test
    void listsAllAccounts() {
        // Test methods in this class share one Testcontainers Postgres instance (no per-test
        // cleanup), so other tests' accounts may already be in the table -- assert this test's
        // own accounts are present in the list rather than asserting an exact total count.
        UUID firstId = createAccount(new BigDecimal("100.00"));
        UUID secondId = createAccount(new BigDecimal("50.00"));

        ResponseEntity<AccountResponse[]> response = restTemplate.getForEntity("/accounts", AccountResponse[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .extracting(AccountResponse::id)
                .contains(firstId, secondId);
    }

    @Test
    void returns404ForUnknownAccount() {
        ResponseEntity<ProblemDetail> response = restTemplate.getForEntity(
                "/accounts/" + UUID.randomUUID(), ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().getProperties()).containsEntry("code", "ACCOUNT_NOT_FOUND");
        assertThat(response.getBody().getProperties()).containsKey("timestamp");
    }

    @Test
    void debitsAnAccountSuccessfully() {
        UUID id = createAccount(new BigDecimal("100.00"));

        ResponseEntity<AccountResponse> response = debit(id, new BigDecimal("40.00"), "debit-key-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().balance()).isEqualByComparingTo("60.00");
    }

    @Test
    void rejectsDebitWithInsufficientFunds() {
        UUID id = createAccount(new BigDecimal("10.00"));

        ResponseEntity<ProblemDetail> response = restTemplate.exchange(
                "/accounts/" + id + "/debit", HttpMethod.POST,
                amountRequest(new BigDecimal("40.00"), "debit-key-2"), ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().getProperties()).containsEntry("code", "INSUFFICIENT_FUNDS");
    }

    @Test
    void creditsAnAccountSuccessfully() {
        UUID id = createAccount(new BigDecimal("100.00"));

        ResponseEntity<AccountResponse> response = credit(id, new BigDecimal("40.00"), "credit-key-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().balance()).isEqualByComparingTo("140.00");
    }

    @Test
    void rejectsADebitWithNoIdempotencyKeyHeader() {
        UUID id = createAccount(new BigDecimal("100.00"));

        ResponseEntity<ProblemDetail> response = restTemplate.postForEntity(
                "/accounts/" + id + "/debit", new AmountRequest(new BigDecimal("40.00")), ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getProperties()).containsEntry("code", "VALIDATION_FAILED");
    }

    @Test
    void replayingTheSameIdempotencyKeyDoesNotDebitTwice() {
        UUID id = createAccount(new BigDecimal("100.00"));

        ResponseEntity<AccountResponse> first = debit(id, new BigDecimal("40.00"), "replay-key");
        ResponseEntity<AccountResponse> second = debit(id, new BigDecimal("40.00"), "replay-key");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<AccountResponse> current = restTemplate.getForEntity("/accounts/" + id, AccountResponse.class);
        assertThat(current.getBody().balance()).isEqualByComparingTo("60.00");
    }

    @Test
    void rejectsADebitWithABlankIdempotencyKeyHeader() {
        UUID id = createAccount(new BigDecimal("100.00"));

        ResponseEntity<ProblemDetail> response = restTemplate.exchange(
                "/accounts/" + id + "/debit", HttpMethod.POST,
                amountRequest(new BigDecimal("40.00"), "   "), ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getProperties()).containsEntry("code", "VALIDATION_FAILED");
    }

    @Test
    void rejectsADebitWithAnOverlongIdempotencyKeyHeader() {
        UUID id = createAccount(new BigDecimal("100.00"));
        // AccountOperation.idempotencyKey is @Column(length = 255).
        String tooLong = "k".repeat(256);

        ResponseEntity<ProblemDetail> response = restTemplate.exchange(
                "/accounts/" + id + "/debit", HttpMethod.POST,
                amountRequest(new BigDecimal("40.00"), tooLong), ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getProperties()).containsEntry("code", "VALIDATION_FAILED");
    }

    @Test
    void reusingAKeyWithADifferentAmountConflicts() {
        UUID id = createAccount(new BigDecimal("100.00"));
        debit(id, new BigDecimal("40.00"), "conflict-key");

        ResponseEntity<ProblemDetail> response = restTemplate.exchange(
                "/accounts/" + id + "/debit", HttpMethod.POST,
                amountRequest(new BigDecimal("15.00"), "conflict-key"), ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getProperties()).containsEntry("code", "IDEMPOTENCY_KEY_CONFLICT");
    }

    @Test
    void returns409ForConcurrentUpdateConflict() throws Exception {
        // A 2-thread version of this test (one barrier-released pair of requests) was flaky
        // in CI: on a slow/constrained runner, both requests can fully serialize (one commits
        // entirely before the other even reads), so no version conflict ever occurs and both
        // return 200 OK instead of one returning 409. Widening to CONCURRENT_REQUESTS
        // genuinely concurrent debits against the same account makes it overwhelmingly likely
        // that at least two of them interleave and collide, without relying on exact
        // microsecond-level thread-scheduling timing. This still exercises the real HTTP debit
        // endpoint end-to-end (not the repository directly) — see AccountRepositoryTest for the
        // deterministic repository-level proof of the same optimistic-locking behavior.
        //
        // Each request uses its OWN idempotency key: giving them all the same key would make
        // the dedup path short-circuit 9 of the 10 requests instead of letting them race.
        int concurrentRequests = 10;
        UUID id = createAccount(new BigDecimal("1000.00"));

        CyclicBarrier barrier = new CyclicBarrier(concurrentRequests);
        List<Callable<ResponseEntity<String>>> debitCalls = new ArrayList<>();
        for (int i = 0; i < concurrentRequests; i++) {
            String key = "concurrent-key-" + i;
            debitCalls.add(() -> {
                barrier.await();
                return restTemplate.exchange(
                        "/accounts/" + id + "/debit", HttpMethod.POST,
                        amountRequest(new BigDecimal("1.00"), key), String.class);
            });
        }

        ExecutorService executor = Executors.newFixedThreadPool(concurrentRequests);
        try {
            List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
            for (Callable<ResponseEntity<String>> call : debitCalls) {
                futures.add(executor.submit(call));
            }

            List<ResponseEntity<String>> responses = new ArrayList<>();
            for (Future<ResponseEntity<String>> future : futures) {
                responses.add(future.get(10, TimeUnit.SECONDS));
            }

            List<HttpStatus> statuses = new ArrayList<>();
            for (ResponseEntity<String> response : responses) {
                statuses.add((HttpStatus) response.getStatusCode());
            }

            assertThat(statuses).hasSize(concurrentRequests);
            assertThat(statuses).allMatch(status -> status == HttpStatus.OK || status == HttpStatus.CONFLICT);
            assertThat(statuses).as("at least one of %s concurrent debits should lose the optimistic-lock race", concurrentRequests)
                    .contains(HttpStatus.CONFLICT);
            // Transfer Service records CONCURRENT_MODIFICATION and aborts (does not retry);
            // retry policy is a later plan's concern. This is the only error code derived
            // from a framework exception type rather than an app-owned one, so it is the most
            // likely to drift silently under a Spring/Hibernate upgrade. The assertion above
            // guarantees at least one CONFLICT, so this filtered check is never vacuous.
            assertThat(responses)
                    .filteredOn(response -> response.getStatusCode() == HttpStatus.CONFLICT)
                    .as("every 409 body must carry the CONCURRENT_MODIFICATION code")
                    .allSatisfy(response -> assertThat(response.getBody()).contains("CONCURRENT_MODIFICATION"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentDebitsWithTheSameIdempotencyKeyNeverDoubleApplyAndTheLoserGets500() throws Exception {
        // A genuine same-key race: unlike returns409ForConcurrentUpdateConflict above (each
        // request uses its OWN key), every request here shares ONE idempotency key, so more
        // than one can pass AccountService.apply()'s existing-operation check as "not yet
        // applied" before either commits. The primary key on idempotency_key then makes
        // every loser's INSERT collide at commit time -- @Transactional rolls the whole
        // method back, so the loser's balance change never persists, and a generic 500 (not
        // 409) is the documented, deliberately-uncaught outcome (see AccountService.apply()'s
        // comment). This proves that outcome live rather than assuming Hibernate's
        // insert-before-update flush ordering -- which is what actually keeps a losing
        // request from double-debiting instead of merely failing loudly -- as Phase 3's
        // final review flagged (docs/roadmap.md).
        int concurrentRequests = 10;
        UUID id = createAccount(new BigDecimal("100.00"));
        String sharedKey = "race-key";

        CyclicBarrier barrier = new CyclicBarrier(concurrentRequests);
        List<Callable<ResponseEntity<String>>> debitCalls = new ArrayList<>();
        for (int i = 0; i < concurrentRequests; i++) {
            debitCalls.add(() -> {
                barrier.await();
                return restTemplate.exchange(
                        "/accounts/" + id + "/debit", HttpMethod.POST,
                        amountRequest(new BigDecimal("40.00"), sharedKey), String.class);
            });
        }

        ExecutorService executor = Executors.newFixedThreadPool(concurrentRequests);
        try {
            List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
            for (Callable<ResponseEntity<String>> call : debitCalls) {
                futures.add(executor.submit(call));
            }

            List<ResponseEntity<String>> responses = new ArrayList<>();
            for (Future<ResponseEntity<String>> future : futures) {
                responses.add(future.get(10, TimeUnit.SECONDS));
            }

            List<HttpStatus> statuses = new ArrayList<>();
            for (ResponseEntity<String> response : responses) {
                statuses.add((HttpStatus) response.getStatusCode());
            }

            assertThat(statuses).hasSize(concurrentRequests);
            // OK covers both the winner and any request that arrived late enough to see the
            // winner's row already committed -- a genuine idempotent replay, not a race loss.
            assertThat(statuses).allMatch(status -> status == HttpStatus.OK || status == HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(statuses)
                    .as("at least one of %s same-key concurrent debits should lose the insert race", concurrentRequests)
                    .contains(HttpStatus.INTERNAL_SERVER_ERROR);

            ResponseEntity<AccountResponse> after = restTemplate.getForEntity("/accounts/" + id, AccountResponse.class);
            assertThat(after.getBody().balance())
                    .as("the debit must land exactly once no matter how many requests raced for the same key")
                    .isEqualByComparingTo("60.00");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsNegativeInitialBalance() {
        ResponseEntity<ProblemDetail> response = restTemplate.postForEntity(
                "/accounts", new CreateAccountRequest("Ada Lovelace", new BigDecimal("-5.00")), ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getProperties()).containsEntry("code", "VALIDATION_FAILED");
    }

    @Test
    void returns405WithCodeForUnsupportedMethod() {
        // Covers the exceptions ResponseEntityExceptionHandler handles for us: they render through
        // handleExceptionInternal, which must stamp the code/timestamp invariant on every problem body.
        UUID id = createAccount(new BigDecimal("100.00"));

        ResponseEntity<ProblemDetail> response = restTemplate.exchange(
                "/accounts/" + id, HttpMethod.DELETE, null, ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().getProperties()).containsEntry("code", "REQUEST_REJECTED");
        assertThat(response.getBody().getProperties()).containsKey("timestamp");
    }

    @Test
    void returns400ForMalformedAccountId() {
        ResponseEntity<ProblemDetail> response = restTemplate.getForEntity(
                "/accounts/not-a-uuid", ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getProperties()).containsEntry("code", "MALFORMED_REQUEST");
    }

    private UUID createAccount(BigDecimal initialBalance) {
        ResponseEntity<AccountResponse> response = restTemplate.postForEntity(
                "/accounts", new CreateAccountRequest("Ada Lovelace", initialBalance), AccountResponse.class);
        return response.getBody().id();
    }

    private HttpEntity<AmountRequest> amountRequest(BigDecimal amount, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", idempotencyKey);
        return new HttpEntity<>(new AmountRequest(amount), headers);
    }

    private ResponseEntity<AccountResponse> debit(UUID id, BigDecimal amount, String idempotencyKey) {
        return restTemplate.exchange(
                "/accounts/" + id + "/debit", HttpMethod.POST, amountRequest(amount, idempotencyKey), AccountResponse.class);
    }

    private ResponseEntity<AccountResponse> credit(UUID id, BigDecimal amount, String idempotencyKey) {
        return restTemplate.exchange(
                "/accounts/" + id + "/credit", HttpMethod.POST, amountRequest(amount, idempotencyKey), AccountResponse.class);
    }
}
