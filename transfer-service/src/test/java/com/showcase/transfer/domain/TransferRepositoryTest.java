package com.showcase.transfer.domain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Testcontainers
class TransferRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TransferRepository transferRepository;

    @Test
    void savesAndReloadsATransfer() {
        Transfer saved = transferRepository.save(
                new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("25.00")));

        Optional<Transfer> found = transferRepository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(TransferStatus.PENDING);
        assertThat(found.get().getAmount()).isEqualByComparingTo("25.00");
    }

    @Test
    void persistsTheFailureCodeAsAString() {
        Transfer transfer = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("25.00"));
        transfer.markFailed(TransferFailureCode.INSUFFICIENT_FUNDS, "not enough money");

        Transfer saved = transferRepository.saveAndFlush(transfer);

        Transfer reloaded = transferRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getFailureCode()).isEqualTo(TransferFailureCode.INSUFFICIENT_FUNDS);
        assertThat(reloaded.getFailureReason()).isEqualTo("not enough money");
        assertThat(reloaded.getSettledAt()).isNotNull();
    }

    @Test
    void findsTransfersByStatus() {
        Transfer stranded = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("25.00"));
        stranded.markCompensationRequired(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, "credit failed");
        transferRepository.saveAndFlush(stranded);

        List<Transfer> found = transferRepository.findByStatus(TransferStatus.COMPENSATION_REQUIRED);

        assertThat(found).extracting(Transfer::getId).contains(stranded.getId());
    }

    @Test
    void findsStalePendingTransfersOlderThanTheCutoff() {
        Transfer stale = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("25.00"));
        ReflectionTestUtils.setField(stale, "createdAt", Instant.now().minus(Duration.ofMinutes(10)));
        transferRepository.saveAndFlush(stale);
        Transfer recent = transferRepository.saveAndFlush(
                new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("25.00")));

        List<Transfer> found = transferRepository.findByStatusAndCreatedAtBefore(
                TransferStatus.PENDING, Instant.now().minus(Duration.ofSeconds(120)));

        assertThat(found).extracting(Transfer::getId).contains(stale.getId());
        assertThat(found).extracting(Transfer::getId).doesNotContain(recent.getId());
    }
}
