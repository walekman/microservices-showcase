package com.showcase.transfer.service;

import com.showcase.transfer.domain.OutboxEvent;
import com.showcase.transfer.domain.OutboxEventRepository;
import com.showcase.transfer.domain.Transfer;
import com.showcase.transfer.domain.TransferFailureCode;
import com.showcase.transfer.domain.TransferRepository;
import com.showcase.transfer.domain.TransferStatus;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Limit;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the real, Spring-managed CompensationScheduler bean against a real Postgres-backed
 * TransferRepository -- unlike CompensationSchedulerTest, which mocks both AccountClient and
 * TransferRepository and so never exercises the real save()/{@code @Version} path this class
 * depends on. Points account-service.base-url at a fake HTTP backend (the same technique
 * AccountClientFallbackIT uses) rather than mocking AccountClient, so the real AOP-decorated
 * bean (CircuitBreaker/Retry/fallback all active) is what gets called too.
 *
 * <p>Covers a Phase 3 final-review test-coverage gap (docs/roadmap.md): no test previously
 * exercised a real scheduled CompensationScheduler execution end-to-end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class CompensationSchedulerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final UUID FROM = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TO = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static HttpServer fakeAccountService;
    private static HttpServer fakeFraudService;
    private static final AtomicInteger creditRequestsToDestination = new AtomicInteger();

    @DynamicPropertySource
    static void accountServiceUrl(DynamicPropertyRegistry registry) throws IOException {
        fakeAccountService = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        // The credit leg to TO always succeeds -- reconcileCredit's happy path: the credit
        // had already landed, nothing to reverse.
        fakeAccountService.createContext("/accounts/" + TO + "/credit", exchange -> {
            creditRequestsToDestination.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        fakeAccountService.start();

        fakeFraudService = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        // The fraud check always succeeds (200 OK, not blocklisted) for reconciliation.
        fakeFraudService.createContext("/fraud-check", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        fakeFraudService.start();

        registry.add("account-service.base-url",
                () -> "http://localhost:" + fakeAccountService.getAddress().getPort());
        registry.add("fraud-service.base-url",
                () -> "http://localhost:" + fakeFraudService.getAddress().getPort());
    }

    @AfterAll
    static void stopFakeAccountService() {
        if (fakeAccountService != null) {
            fakeAccountService.stop(0);
        }
        if (fakeFraudService != null) {
            fakeFraudService.stop(0);
        }
    }

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private CompensationScheduler scheduler;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Test
    void drainingARealStrandedTransferReconcilesItToCompletedAndPersistsThroughTheRealVersionedSave() {
        Transfer transfer = new Transfer(FROM, TO, new BigDecimal("40.00"));
        transfer.markCompensationRequired(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, "credit leg timed out");
        transfer = transferRepository.save(transfer);
        long versionBeforeSweep = transfer.getVersion();

        scheduler.drainCompensationRequired();

        Transfer reconciled = transferRepository.findById(transfer.getId()).orElseThrow();
        assertThat(reconciled.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        // Proves the real optimistic-locked save() path actually ran, not a mock: a genuine
        // JPA update against the real row bumps @Version.
        assertThat(reconciled.getVersion()).isGreaterThan(versionBeforeSweep);
        assertThat(reconciled.getFailureCode()).isNull();
        assertThat(reconciled.getFailureReason()).isNull();
        assertThat(creditRequestsToDestination.get()).isGreaterThanOrEqualTo(1);

        // This is the one test in the suite where TransferSaveService is the real,
        // Spring-managed bean (not a mock) running against a real database -- the strongest
        // proof available that reconcileCredit()'s transferSaveService.save(transfer) call
        // actually wrote an outbox row end-to-end, not just that the Transfer's own status
        // changed. No Kafka container runs in this test, so nothing could have published (and
        // thus unpublished-marked-null) this row out from under the assertion.
        List<OutboxEvent> unpublished = outboxEventRepository.findByPublishedAtIsNullOrderByCreatedAtAsc(Limit.of(10));
        assertThat(unpublished).extracting(OutboxEvent::getTransferId).contains(transfer.getId());
    }
}
