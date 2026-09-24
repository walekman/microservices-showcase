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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

// The two concurrency tests below (returns409ForConcurrentUpdateConflict and
// concurrentDebitsWithTheSameIdempotencyKeyNeverDoubleApplyAndTheLoserGets500) do not rely on
// requests happening to overlap: they hold the account row locked in Postgres while the requests
// are fired, so every request is parked mid-transaction before any of them can commit. See
// sendWhileAccountRowIsLocked. They used to release the requests with a CyclicBarrier and hope,
// and the race window (a few ms between one request's idempotency-key lookup and its commit) was
// narrower than the HTTP dispatch jitter on CI, so they flaked with every request returning 200 OK
// -- see docs/investigation-account-controller-it-concurrency-flake.md.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(TestSecurityConfig.class)
class AccountControllerIT {

    // Two is enough: the row lock makes them collide every time, so more requests would only
    // add connections, not confidence.
    private static final int CONCURRENT_REQUESTS = 2;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeEach
    void authenticateAsCustomer() {
        TestSecurityConfig.authenticateAsCustomer(restTemplate);
    }

    /**
     * Sends {@code requests} concurrently while this test holds the account's row lock, releases
     * the lock only once every request is blocked inside Postgres, and returns their responses.
     *
     * <p>A debit reads without locking (plain {@code SELECT}s are not blocked by
     * {@code FOR UPDATE}), so every request passes its idempotency-key check and loads the account
     * before it blocks. What it blocks on is the {@code UPDATE accounts}, or, for a same-key
     * request, the {@code INSERT} of a primary key another open transaction has already written.
     * Nothing can commit until the lock is released, so the requests are guaranteed to have
     * overlapped, whatever the HTTP stack's timing.
     *
     * <p>The lock is taken on a connection of the test's own, outside the application's pool. It
     * touches nothing in AccountService's dependency graph, so the race is still a real one
     * between real transactions.
     */
    private List<ResponseEntity<String>> sendWhileAccountRowIsLocked(
            UUID accountId, List<Callable<ResponseEntity<String>>> requests) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(requests.size());
        try (Connection lockHolder = postgres.createConnection("")) {
            lockHolder.setAutoCommit(false);
            try (PreparedStatement lock = lockHolder.prepareStatement("SELECT 1 FROM accounts WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, accountId);
                try (ResultSet row = lock.executeQuery()) {
                    assertThat(row.next()).as("account %s must exist to be locked", accountId).isTrue();
                }
            }

            List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
            try {
                for (Callable<ResponseEntity<String>> request : requests) {
                    futures.add(executor.submit(request));
                }
                awaitTransactionsBlockedOnLocks(requests.size());
            } finally {
                // Also on failure, so a timed-out wait never leaves the requests hanging.
                lockHolder.rollback();
            }

            List<ResponseEntity<String>> responses = new ArrayList<>();
            for (Future<ResponseEntity<String>> future : futures) {
                responses.add(future.get(10, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            executor.shutdownNow();
        }
    }

    // pg_locks, not pg_stat_activity: the latter is snapshotted once per transaction.
    private void awaitTransactionsBlockedOnLocks(int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int blocked = 0;
        try (Connection observer = postgres.createConnection("");
             PreparedStatement query = observer.prepareStatement("SELECT count(DISTINCT pid) FROM pg_locks WHERE NOT granted")) {
            while (System.nanoTime() < deadline) {
                try (ResultSet result = query.executeQuery()) {
                    result.next();
                    blocked = result.getInt(1);
                }
                if (blocked == expected) {
                    return;
                }
                Thread.sleep(20);
            }
        }
        fail("only %s of %s requests reached a lock wait in Postgres, so they cannot be proven to have raced",
                blocked, expected);
    }

    @Test
    void createsAndFetchesAnAccount() {
        ResponseEntity<AccountResponse> createResponse = restTemplate.postForEntity(
                "/accounts", new CreateAccountRequest("Ada Lovelace", new BigDecimal("100.00")), AccountResponse.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID id = createResponse.getBody().id();

        // AccountResponse deliberately carries no ownerId (see its own javadoc) -- this GET
        // succeeding, using the same fresh per-test-method identity (installed by
        // authenticateAsCustomer in @BeforeEach) that created the account, is the regression
        // check that ownerId was actually bound to the caller: a mismatched binding would 404
        // here instead.
        ResponseEntity<AccountResponse> getResponse = restTemplate.getForEntity("/accounts/" + id, AccountResponse.class);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody().balance()).isEqualByComparingTo("100.00");
    }

    @Test
    void listsAllAccounts() {
        // Test methods in this class share one Testcontainers Postgres instance (no per-test
        // cleanup), so other tests' accounts may already be in the table -- assert this test's
        // own accounts are present in the list rather than asserting an exact total count.
        UUID firstId = createAccount(new BigDecimal("100.00"), TestSecurityConfig.freshCustomerToken());
        UUID secondId = createAccount(new BigDecimal("50.00"), TestSecurityConfig.freshCustomerToken());

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

    @Test
    void listsOnlyMyAccounts() {
        UUID myAccountId = createAccount(new BigDecimal("100.00"));

        ResponseEntity<AccountResponse[]> response = restTemplate.getForEntity("/accounts/mine", AccountResponse[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).extracting(AccountResponse::id).containsExactly(myAccountId);
    }

    // Regression guard for the literal-vs-variable route ambiguity: "/accounts/mine" and
    // "/accounts/{id}" are both single-segment GET paths on this controller. Spring MVC's
    // PathPattern comparator always prefers the more specific (literal) match, so this must
    // return the caller's own accounts, never attempt UUID.fromString("mine") for getAccount.
    @Test
    void accountsMineIsNotSwallowedByTheIdRoute() {
        ResponseEntity<AccountResponse[]> response = restTemplate.getForEntity("/accounts/mine", AccountResponse[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
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
    void refusesACreditFromACustomerAndLeavesTheBalanceUntouched() {
        // A real customer token (account-editor, no account-crediter) crediting directly -- the
        // money-minting path that was once an open item. The balance check proves nothing moved.
        UUID id = createAccount(new BigDecimal("100.00"));
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", "credit-key-customer");
        headers.setBearerAuth(TestSecurityConfig.freshCustomerToken());

        ResponseEntity<String> response = restTemplate.exchange("/accounts/" + id + "/credit", HttpMethod.POST,
                new HttpEntity<>(new AmountRequest(new BigDecimal("1000000.00")), headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(restTemplate.getForEntity("/accounts/" + id, AccountResponse.class).getBody().balance())
                .isEqualByComparingTo("100.00");
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
        // Each request uses its OWN idempotency key, so both get past the dedup check and write
        // their own operation row, then block on the locked account's UPDATE. Once released, the
        // first one commits; Postgres re-evaluates the second one's `WHERE version = ?` against
        // the new row version, updates nothing, and Hibernate reports the optimistic-lock
        // failure. This is the real HTTP debit endpoint end to end -- see AccountRepositoryTest
        // for the repository-level proof of the same optimistic-locking behavior.
        UUID id = createAccount(new BigDecimal("1000.00"));

        List<Callable<ResponseEntity<String>>> debitCalls = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            String key = "concurrent-key-" + i;
            debitCalls.add(() -> restTemplate.exchange(
                    "/accounts/" + id + "/debit", HttpMethod.POST,
                    amountRequest(new BigDecimal("1.00"), key), String.class));
        }

        List<ResponseEntity<String>> responses = sendWhileAccountRowIsLocked(id, debitCalls);

        assertThat(statusesOf(responses))
                .as("exactly one of %s concurrent debits wins and the rest lose the optimistic-lock race", CONCURRENT_REQUESTS)
                .containsExactlyInAnyOrderElementsOf(oneWinnerAndLosers(HttpStatus.CONFLICT));
        // Transfer Service records CONCURRENT_MODIFICATION and aborts (does not retry);
        // retry policy is a later plan's concern. This is the only error code derived
        // from a framework exception type rather than an app-owned one, so it is the most
        // likely to drift silently under a Spring/Hibernate upgrade.
        assertThat(responses)
                .filteredOn(response -> response.getStatusCode() == HttpStatus.CONFLICT)
                .as("every 409 body must carry the CONCURRENT_MODIFICATION code")
                .allSatisfy(response -> assertThat(response.getBody()).contains("CONCURRENT_MODIFICATION"));
        assertThat(restTemplate.getForEntity("/accounts/" + id, AccountResponse.class).getBody().balance())
                .as("only the winning debit lands")
                .isEqualByComparingTo("999.00");
    }

    @Test
    void concurrentDebitsWithTheSameIdempotencyKeyNeverDoubleApplyAndTheLoserGets500() throws Exception {
        // A genuine same-key race: unlike returns409ForConcurrentUpdateConflict above (each
        // request uses its OWN key), every request here shares ONE idempotency key, and all of
        // them pass AccountService.apply()'s existing-operation check as "not yet applied",
        // because nothing can commit while the test holds the account row. The first request's
        // INSERT succeeds and it blocks on the locked UPDATE; every other request's INSERT of
        // the same primary key blocks behind it, and fails once the winner commits.
        // @Transactional rolls the loser back, so its balance change never persists, and a
        // generic 500 (not 409) is the documented, deliberately-uncaught outcome (see
        // AccountService.apply()'s comment).
        //
        // This proves Hibernate's insert-before-update flush ordering live rather than assuming
        // it -- which is what actually keeps a losing request from double-debiting instead of
        // merely failing loudly -- as Phase 3's final review flagged (docs/roadmap.md). Were the
        // UPDATE flushed first, every request would queue on the row lock and the losers would
        // get 409s instead, failing this test.
        UUID id = createAccount(new BigDecimal("100.00"));
        String sharedKey = "race-key";

        List<Callable<ResponseEntity<String>>> debitCalls = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            debitCalls.add(() -> restTemplate.exchange(
                    "/accounts/" + id + "/debit", HttpMethod.POST,
                    amountRequest(new BigDecimal("40.00"), sharedKey), String.class));
        }

        List<ResponseEntity<String>> responses = sendWhileAccountRowIsLocked(id, debitCalls);

        assertThat(statusesOf(responses))
                .as("exactly one of %s same-key concurrent debits wins and the rest lose the insert race", CONCURRENT_REQUESTS)
                .containsExactlyInAnyOrderElementsOf(oneWinnerAndLosers(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThat(restTemplate.getForEntity("/accounts/" + id, AccountResponse.class).getBody().balance())
                .as("the debit must land exactly once no matter how many requests raced for the same key")
                .isEqualByComparingTo("60.00");
    }

    private static List<HttpStatus> statusesOf(List<ResponseEntity<String>> responses) {
        List<HttpStatus> statuses = new ArrayList<>();
        for (ResponseEntity<String> response : responses) {
            statuses.add((HttpStatus) response.getStatusCode());
        }
        return statuses;
    }

    private static List<HttpStatus> oneWinnerAndLosers(HttpStatus loserStatus) {
        List<HttpStatus> expected = new ArrayList<>(List.of(HttpStatus.OK));
        for (int i = 1; i < CONCURRENT_REQUESTS; i++) {
            expected.add(loserStatus);
        }
        return expected;
    }

    @Test
    void rejectsASecondAccountForTheSameOwner() {
        createAccount(new BigDecimal("100.00"));

        ResponseEntity<ProblemDetail> response = restTemplate.postForEntity(
                "/accounts", new CreateAccountRequest("Ada Lovelace", new BigDecimal("50.00")), ProblemDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getProperties()).containsEntry("code", "ACCOUNT_ALREADY_EXISTS");
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

    // Deliberately the one place a caller can read something about an account they don't own --
    // the display name, never the balance or ownerId. See docs/phase-9-bank-ui.md's Design
    // Decisions.
    @Test
    void anyAuthenticatedCallerCanReadAnAccountsSummary() {
        UUID id = createAccount(new BigDecimal("100.00"));

        HttpHeaders otherCustomer = new HttpHeaders();
        otherCustomer.setBearerAuth(TestSecurityConfig.freshCustomerToken());
        ResponseEntity<AccountSummaryResponse> response = restTemplate.exchange(
                "/accounts/" + id + "/summary", HttpMethod.GET, new HttpEntity<>(otherCustomer), AccountSummaryResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().ownerName()).isEqualTo("Ada Lovelace");
    }

    @Test
    void summaryReturns404ForAnUnknownAccount() {
        ResponseEntity<ProblemDetail> response = restTemplate.getForEntity(
                "/accounts/" + UUID.randomUUID() + "/summary", ProblemDetail.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getProperties()).containsEntry("code", "ACCOUNT_NOT_FOUND");
    }

    private UUID createAccount(BigDecimal initialBalance) {
        return createAccount(initialBalance, null);
    }

    // ownerToken == null uses the ambient per-test identity from authenticateAsCustomer.
    // An explicit token lets one test method create accounts for two DIFFERENT owners --
    // needed now that one account per owner is enforced. See listsAllAccounts.
    private UUID createAccount(BigDecimal initialBalance, String ownerToken) {
        HttpHeaders headers = new HttpHeaders();
        if (ownerToken != null) {
            headers.setBearerAuth(ownerToken);
        }
        ResponseEntity<AccountResponse> response = restTemplate.exchange(
                "/accounts", HttpMethod.POST,
                new HttpEntity<>(new CreateAccountRequest("Ada Lovelace", initialBalance), headers),
                AccountResponse.class);
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

    // As transfer-service: credit requires account-crediter, which no customer holds.
    private ResponseEntity<AccountResponse> credit(UUID id, BigDecimal amount, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", idempotencyKey);
        headers.setBearerAuth(TestSecurityConfig.TRANSFER_SERVICE_TOKEN);
        return restTemplate.exchange("/accounts/" + id + "/credit", HttpMethod.POST,
                new HttpEntity<>(new AmountRequest(amount), headers), AccountResponse.class);
    }
}
