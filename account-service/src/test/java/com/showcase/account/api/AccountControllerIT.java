package com.showcase.account.api;

import com.showcase.account.support.TestSecurityConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
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

import javax.sql.DataSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
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

// The two concurrency tests below (returns409ForConcurrentUpdateConflict and
// concurrentDebitsWithTheSameIdempotencyKeyNeverDoubleApplyAndTheLoserGets500) only prove
// anything if CONCURRENT_REQUESTS transactions can genuinely be in flight at the same instant,
// and a JDBC transaction holds its pooled connection from begin to commit. So the connection
// pool -- not thread scheduling -- is what actually caps this test's concurrency: with fewer
// than CONCURRENT_REQUESTS connections available, requests queue in HikariCP and each one only
// starts after the previous holder has ALREADY COMMITTED, which is precisely the serialization
// these tests exist to rule out. Both of these tests were flaky in CI with the all-200-OK
// signature that outcome produces.
//
// Hence this test class owns its pool sizing outright rather than inheriting application.yml's
// (absent) setting, HikariCP's default of 10, or whatever a `-D` override supplies: an ambient
// pool smaller than CONCURRENT_REQUESTS silently turns a race test into a no-race test.
// maximum-pool-size deliberately leaves headroom above CONCURRENT_REQUESTS so no request has to
// wait for a connection at all, and minimum-idle matches it so housekeeping never shrinks the
// pool back down between test methods. warmUpConnectionPool() then makes the sizing real --
// see its javadoc for why the configured size alone is not enough.
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.hikari.maximum-pool-size=20",
                "spring.datasource.hikari.minimum-idle=20"
        })
@Testcontainers
@Import(TestSecurityConfig.class)
class AccountControllerIT {

    private static final int CONCURRENT_REQUESTS = 10;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void authenticateAsCustomer() {
        TestSecurityConfig.authenticateAsCustomer(restTemplate);
    }

    /**
     * Blocks until HikariCP has actually opened every connection it is configured for, and
     * asserts it got there.
     *
     * <p>Configuring the pool size is not by itself enough. HikariCP opens exactly ONE
     * connection eagerly at startup; the rest are filled in by a single-threaded
     * "connection-adder" executor, one real TCP + Postgres auth handshake at a time. A burst of
     * requests fired while that fill is still in progress hits a pool that is only a few
     * connections deep, and the surplus requests queue -- the exact serialization these
     * concurrency tests must not silently degrade into. Locally that fill takes ~450ms and
     * finishes seconds before any test method runs, which is why the flake was never reproducible
     * here; on a loaded CI runner with Postgres in Docker each handshake is far slower, so the
     * window stays open much longer.
     *
     * <p>Holding maximumPoolSize connections simultaneously forces the pool to materialize all of
     * them synchronously (each {@code getConnection()} blocks until its connection exists),
     * turning HikariCP's background, best-effort fill into a completed precondition. Closing them
     * returns them to the pool as idle -- {@code minimum-idle == maximum-pool-size} means
     * housekeeping will not evict them afterwards.
     *
     * <p>This touches only the DataSource, i.e. infrastructure underneath the code under test --
     * it does not stub, wrap or reorder anything in AccountService's dependency graph, so the
     * race it enables is still a real one between real transactions against real Postgres.
     */
    private void warmUpConnectionPool() throws SQLException {
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        int poolSize = hikari.getMaximumPoolSize();
        assertThat(poolSize)
                .as("pool must hold more than the %s simultaneous transactions this test needs, "
                        + "or requests serialize behind connection acquisition and no race occurs", CONCURRENT_REQUESTS)
                .isGreaterThan(CONCURRENT_REQUESTS);

        List<Connection> held = new ArrayList<>();
        try {
            for (int i = 0; i < poolSize; i++) {
                held.add(dataSource.getConnection());
            }
        } finally {
            for (Connection connection : held) {
                connection.close();
            }
        }

        // Fails loudly if the pool could not reach its configured size, rather than letting the
        // race test quietly pass with every request returning 200 OK because it never raced.
        assertThat(hikari.getHikariPoolMXBean().getTotalConnections())
                .as("connection pool must be fully open before a barrier-released burst")
                .isEqualTo(poolSize);
    }

    @Test
    void createsAndFetchesAnAccount() {
        ResponseEntity<AccountResponse> createResponse = restTemplate.postForEntity(
                "/accounts", new CreateAccountRequest("Ada Lovelace", new BigDecimal("100.00")), AccountResponse.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID id = createResponse.getBody().id();

        // AccountResponse deliberately carries no ownerId (see its own javadoc) -- this GET
        // succeeding, using the same CUSTOMER_SUBJECT token the account was created with, is
        // the regression check that ownerId was actually bound to the caller: a mismatched
        // binding would 404 here instead.
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

        // account-admin only, as of Phase 7b -- the class-wide customer token (account-reader)
        // is no longer enough, so this request carries its own explicit admin Authorization
        // header, which BearerAuthInterceptor is written to respect over the customer one.
        HttpHeaders adminHeaders = new HttpHeaders();
        adminHeaders.setBearerAuth(TestSecurityConfig.ADMIN_TOKEN);
        ResponseEntity<AccountResponse[]> response = restTemplate.exchange(
                "/accounts", HttpMethod.GET, new HttpEntity<>(adminHeaders), AccountResponse[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .extracting(AccountResponse::id)
                .contains(firstId, secondId);
    }

    // Real end-to-end proof of ownership denial: two DIFFERENT customer identities, both real
    // requests through the full filter chain and AccountService against the real repository --
    // not AccountServiceTest's mocked repository, and not AccountSecurityIT's mocked
    // AccountService. Found missing in code review: without this, nothing actually proved a
    // second real customer gets denied, only that a mocked service returning
    // AccountNotFoundException maps to 404 correctly.
    @Test
    void aDifferentCustomerCannotReadOrDebitAnotherCustomersAccount() {
        UUID id = createAccount(new BigDecimal("100.00"));

        HttpHeaders customer2Headers = new HttpHeaders();
        customer2Headers.setBearerAuth(TestSecurityConfig.CUSTOMER2_TOKEN);

        ResponseEntity<ProblemDetail> getResponse = restTemplate.exchange(
                "/accounts/" + id, HttpMethod.GET, new HttpEntity<>(customer2Headers), ProblemDetail.class);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(getResponse.getBody().getProperties()).containsEntry("code", "ACCOUNT_NOT_FOUND");

        HttpHeaders debitHeaders = new HttpHeaders();
        debitHeaders.setBearerAuth(TestSecurityConfig.CUSTOMER2_TOKEN);
        debitHeaders.set("Idempotency-Key", "cross-customer-debit-key");
        ResponseEntity<ProblemDetail> debitResponse = restTemplate.exchange(
                "/accounts/" + id + "/debit", HttpMethod.POST,
                new HttpEntity<>(new AmountRequest(new BigDecimal("40.00")), debitHeaders), ProblemDetail.class);
        assertThat(debitResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(debitResponse.getBody().getProperties()).containsEntry("code", "ACCOUNT_NOT_FOUND");

        // The account itself is untouched -- confirm with the OWNING customer's token.
        ResponseEntity<AccountResponse> ownerResponse = restTemplate.getForEntity("/accounts/" + id, AccountResponse.class);
        assertThat(ownerResponse.getBody().balance()).isEqualByComparingTo("100.00");
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
        int concurrentRequests = CONCURRENT_REQUESTS;
        warmUpConnectionPool();
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

    @Disabled("Flaky on CI: every request returns 200 OK, so the same-key race never happens. "
            + "See docs/investigation-account-controller-it-concurrency-flake.md; disabled until it is "
            + "rewritten to drive the race deterministically (that doc's Recommendations 2 and 3).")
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
        int concurrentRequests = CONCURRENT_REQUESTS;
        // Without this, a pool that is still filling (or configured smaller than
        // concurrentRequests) makes every request but the first wait for a connection that is
        // only released once the winner has committed -- so every request sees the operation
        // already applied, all of them return 200 OK, and the insert race never happens.
        warmUpConnectionPool();
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
