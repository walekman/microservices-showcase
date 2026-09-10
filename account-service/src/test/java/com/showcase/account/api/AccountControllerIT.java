package com.showcase.account.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
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
class AccountControllerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate restTemplate;

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
        ResponseEntity<ErrorResponse> response = restTemplate.getForEntity(
                "/accounts/" + UUID.randomUUID(), ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void debitsAnAccountSuccessfully() {
        UUID id = createAccount(new BigDecimal("100.00"));

        ResponseEntity<AccountResponse> response = restTemplate.postForEntity(
                "/accounts/" + id + "/debit", new AmountRequest(new BigDecimal("40.00")), AccountResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().balance()).isEqualByComparingTo("60.00");
    }

    @Test
    void rejectsDebitWithInsufficientFunds() {
        UUID id = createAccount(new BigDecimal("10.00"));

        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
                "/accounts/" + id + "/debit", new AmountRequest(new BigDecimal("40.00")), ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void creditsAnAccountSuccessfully() {
        UUID id = createAccount(new BigDecimal("100.00"));

        ResponseEntity<AccountResponse> response = restTemplate.postForEntity(
                "/accounts/" + id + "/credit", new AmountRequest(new BigDecimal("40.00")), AccountResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().balance()).isEqualByComparingTo("140.00");
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
        int concurrentRequests = 10;
        UUID id = createAccount(new BigDecimal("1000.00"));

        CyclicBarrier barrier = new CyclicBarrier(concurrentRequests);
        Callable<ResponseEntity<String>> debitCall = () -> {
            barrier.await();
            return restTemplate.postForEntity(
                    "/accounts/" + id + "/debit", new AmountRequest(new BigDecimal("1.00")), String.class);
        };

        ExecutorService executor = Executors.newFixedThreadPool(concurrentRequests);
        try {
            List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
            for (int i = 0; i < concurrentRequests; i++) {
                futures.add(executor.submit(debitCall));
            }

            List<HttpStatus> statuses = new ArrayList<>();
            for (Future<ResponseEntity<String>> future : futures) {
                statuses.add((HttpStatus) future.get(10, TimeUnit.SECONDS).getStatusCode());
            }

            assertThat(statuses).hasSize(concurrentRequests);
            assertThat(statuses).allMatch(status -> status == HttpStatus.OK || status == HttpStatus.CONFLICT);
            assertThat(statuses).as("at least one of %s concurrent debits should lose the optimistic-lock race", concurrentRequests)
                    .contains(HttpStatus.CONFLICT);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsNegativeInitialBalance() {
        ResponseEntity<ErrorResponse> response = restTemplate.postForEntity(
                "/accounts", new CreateAccountRequest("Ada Lovelace", new BigDecimal("-5.00")), ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private UUID createAccount(BigDecimal initialBalance) {
        ResponseEntity<AccountResponse> response = restTemplate.postForEntity(
                "/accounts", new CreateAccountRequest("Ada Lovelace", initialBalance), AccountResponse.class);
        return response.getBody().id();
    }
}
