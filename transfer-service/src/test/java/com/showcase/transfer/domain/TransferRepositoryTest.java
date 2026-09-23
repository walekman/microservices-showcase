package com.showcase.transfer.domain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
                new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("25.00"), UUID.randomUUID()));

        Optional<Transfer> found = transferRepository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(TransferStatus.PENDING);
        assertThat(found.get().getAmount()).isEqualByComparingTo("25.00");
    }

    @Test
    void persistsTheFailureCodeAsAString() {
        Transfer transfer = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("25.00"), UUID.randomUUID());
        transfer.markFailed(TransferFailureCode.INSUFFICIENT_FUNDS, "not enough money");

        Transfer saved = transferRepository.saveAndFlush(transfer);

        Transfer reloaded = transferRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getFailureCode()).isEqualTo(TransferFailureCode.INSUFFICIENT_FUNDS);
        assertThat(reloaded.getFailureReason()).isEqualTo("not enough money");
        assertThat(reloaded.getSettledAt()).isNotNull();
    }

    @Test
    void findsTransfersByStatus() {
        Transfer stranded = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("25.00"), UUID.randomUUID());
        stranded.markCompensationRequired(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, "credit failed");
        transferRepository.saveAndFlush(stranded);

        List<Transfer> found = transferRepository.findByStatus(TransferStatus.COMPENSATION_REQUIRED);

        assertThat(found).extracting(Transfer::getId).contains(stranded.getId());
    }

    @Test
    void findsStalePendingTransfersOlderThanTheCutoff() {
        Transfer stale = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("25.00"), UUID.randomUUID());
        ReflectionTestUtils.setField(stale, "createdAt", Instant.now().minus(Duration.ofMinutes(10)));
        transferRepository.saveAndFlush(stale);
        Transfer recent = transferRepository.saveAndFlush(
                new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("25.00"), UUID.randomUUID()));

        List<Transfer> found = transferRepository.findByStatusAndCreatedAtBefore(
                TransferStatus.PENDING, Instant.now().minus(Duration.ofSeconds(120)), Limit.unlimited());

        assertThat(found).extracting(Transfer::getId).contains(stale.getId());
        assertThat(found).extracting(Transfer::getId).doesNotContain(recent.getId());
    }

    @Test
    void findByStatusHonoursTheLimit() {
        Transfer first = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("25.00"), UUID.randomUUID());
        first.markCompensationRequired(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, "credit failed");
        transferRepository.saveAndFlush(first);
        Transfer second = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("25.00"), UUID.randomUUID());
        second.markCompensationRequired(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, "credit failed");
        transferRepository.saveAndFlush(second);

        List<Transfer> found = transferRepository.findByStatus(TransferStatus.COMPENSATION_REQUIRED, Limit.of(1));

        assertThat(found).hasSize(1);
    }

    @Test
    void findByStatusAndCreatedAtBeforeHonoursTheLimit() {
        Transfer first = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("25.00"), UUID.randomUUID());
        ReflectionTestUtils.setField(first, "createdAt", Instant.now().minus(Duration.ofMinutes(10)));
        transferRepository.saveAndFlush(first);
        Transfer second = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("25.00"), UUID.randomUUID());
        ReflectionTestUtils.setField(second, "createdAt", Instant.now().minus(Duration.ofMinutes(10)));
        transferRepository.saveAndFlush(second);

        List<Transfer> found = transferRepository.findByStatusAndCreatedAtBefore(
                TransferStatus.PENDING, Instant.now().minus(Duration.ofSeconds(120)), Limit.of(1));

        assertThat(found).hasSize(1);
    }

    @Test
    void findsATransferByInitiatorAndIdempotencyKey() {
        UUID initiator = UUID.randomUUID();
        Transfer saved = transferRepository.saveAndFlush(
                new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("25.00"), initiator, "key-a"));

        assertThat(transferRepository.findByInitiatorIdAndIdempotencyKey(initiator, "key-a"))
                .map(Transfer::getId).contains(saved.getId());
        // Scoped per initiator: another caller's lookup with the same key finds nothing.
        assertThat(transferRepository.findByInitiatorIdAndIdempotencyKey(UUID.randomUUID(), "key-a")).isEmpty();
    }

    @Test
    void rejectsADuplicateIdempotencyKeyForTheSameInitiator() {
        UUID initiator = UUID.randomUUID();
        transferRepository.saveAndFlush(
                new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("25.00"), initiator, "key-b"));

        assertThatThrownBy(() -> transferRepository.saveAndFlush(
                new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("25.00"), initiator, "key-b")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsTheSameIdempotencyKeyForDifferentInitiatorsAndManyKeylessRows() {
        transferRepository.saveAndFlush(
                new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("25.00"), UUID.randomUUID(), "key-c"));
        transferRepository.saveAndFlush(
                new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("25.00"), UUID.randomUUID(), "key-c"));

        // Pre-idempotency rows have a null key; the constraint must not treat them as duplicates.
        UUID initiator = UUID.randomUUID();
        transferRepository.saveAndFlush(
                new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("25.00"), initiator));
        transferRepository.saveAndFlush(
                new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("25.00"), initiator));
    }
}
