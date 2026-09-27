package com.showcase.fraud.domain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Testcontainers
class BlockedAccountRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private BlockedAccountRepository repository;

    @Test
    void blockingIsIdempotentAndKeepsTheFirstTimestamp() {
        UUID accountId = UUID.randomUUID();
        Instant first = Instant.now().truncatedTo(ChronoUnit.MICROS);

        assertThat(repository.blockIfAbsent(accountId, first)).isEqualTo(1);
        assertThat(repository.blockIfAbsent(accountId, first.plusSeconds(60))).isEqualTo(0);

        assertThat(repository.existsById(accountId)).isTrue();
        assertThat(repository.findById(accountId)).get()
                .extracting(BlockedAccount::getBlockedAt).isEqualTo(first);
    }

    @Test
    void unblockingRemovesTheRowAndIsIdempotent() {
        UUID accountId = UUID.randomUUID();
        repository.blockIfAbsent(accountId, Instant.now());

        assertThat(repository.unblock(accountId)).isEqualTo(1);
        assertThat(repository.unblock(accountId)).isEqualTo(0);
        assertThat(repository.existsById(accountId)).isFalse();
    }

    // Outside the test transaction: each call must commit on its own, as it does in production,
    // or the two inserts would never actually race.
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentBlocksOfTheSameAccountBothSucceed() throws Exception {
        UUID accountId = UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<CompletableFuture<Integer>> calls = List.of(
                    CompletableFuture.supplyAsync(() -> blockAfter(start, accountId), pool),
                    CompletableFuture.supplyAsync(() -> blockAfter(start, accountId), pool));
            start.countDown();

            List<Integer> inserted = calls.stream().map(CompletableFuture::join).toList();

            assertThat(inserted).containsExactlyInAnyOrder(0, 1);
            assertThat(repository.count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
            repository.unblock(accountId);
        }
    }

    private int blockAfter(CountDownLatch start, UUID accountId) {
        try {
            start.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
        return repository.blockIfAbsent(accountId, Instant.now());
    }
}
