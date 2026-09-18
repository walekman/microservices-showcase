// transfer-service/src/test/java/com/showcase/transfer/service/TransferSaveServiceTest.java
package com.showcase.transfer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.showcase.transfer.domain.OutboxEventRepository;
import com.showcase.transfer.domain.Transfer;
import com.showcase.transfer.domain.TransferFailureCode;
import com.showcase.transfer.domain.TransferRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferSaveServiceTest {

    @Mock
    private TransferRepository transferRepository;
    @Mock
    private OutboxEventRepository outboxEventRepository;

    private SimpleMeterRegistry meterRegistry;
    private TransferSaveService service;

    @BeforeEach
    void setUp() {
        // A plain ObjectMapper does not know how to serialize java.time.Instant without this
        // module -- Spring's auto-configured bean has it registered already, but a
        // hand-built one in a unit test needs it explicitly, or toPayload() throws.
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        meterRegistry = new SimpleMeterRegistry();
        service = new TransferSaveService(transferRepository, outboxEventRepository, objectMapper, meterRegistry);
    }

    @Test
    void writesAnOutboxRowForACompletedTransfer() {
        Transfer transfer = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), UUID.randomUUID());
        transfer.markCompleted();
        when(transferRepository.save(transfer)).thenReturn(transfer);

        service.save(transfer);

        verify(outboxEventRepository).save(any());
    }

    @Test
    void writesNoOutboxRowForATransientStatus() {
        Transfer transfer = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), UUID.randomUUID());
        transfer.markCompensationRequired(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, "credit leg timed out");
        when(transferRepository.save(transfer)).thenReturn(transfer);

        service.save(transfer);

        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    void writesAnOutboxRowForEachTerminalStatus() {
        Transfer completed = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), UUID.randomUUID());
        completed.markCompleted();
        Transfer failed = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), UUID.randomUUID());
        failed.markFailed(TransferFailureCode.INSUFFICIENT_FUNDS, "not enough money");
        when(transferRepository.save(completed)).thenReturn(completed);
        when(transferRepository.save(failed)).thenReturn(failed);

        service.save(completed);
        service.save(failed);

        verify(outboxEventRepository, org.mockito.Mockito.times(2)).save(any());
    }

    @Test
    void incrementsCompletedCounterForACompletedTransfer() {
        Transfer transfer = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), UUID.randomUUID());
        transfer.markCompleted();
        when(transferRepository.save(transfer)).thenReturn(transfer);

        service.save(transfer);

        assertThat(meterRegistry.counter("transfers.completed").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("transfers.failed").count()).isEqualTo(0.0);
    }

    @Test
    void incrementsFailedCounterForAFailedTransfer() {
        Transfer transfer = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), UUID.randomUUID());
        transfer.markFailed(TransferFailureCode.INSUFFICIENT_FUNDS, "not enough money");
        when(transferRepository.save(transfer)).thenReturn(transfer);

        service.save(transfer);

        assertThat(meterRegistry.counter("transfers.failed").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("transfers.fraud_rejected").count()).isEqualTo(0.0);
    }

    @Test
    void incrementsFraudRejectedCounterAlongsideFailedForABlockedSourceAccount() {
        Transfer transfer = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), UUID.randomUUID());
        transfer.markFailed(TransferFailureCode.SOURCE_ACCOUNT_BLOCKED, "source blocked");
        when(transferRepository.save(transfer)).thenReturn(transfer);

        service.save(transfer);

        assertThat(meterRegistry.counter("transfers.failed").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("transfers.fraud_rejected").count()).isEqualTo(1.0);
    }

    @Test
    void incrementsFraudRejectedCounterForABlockedDestinationAccount() {
        Transfer transfer = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), UUID.randomUUID());
        transfer.markFailed(TransferFailureCode.DESTINATION_ACCOUNT_BLOCKED, "destination blocked");
        when(transferRepository.save(transfer)).thenReturn(transfer);

        service.save(transfer);

        assertThat(meterRegistry.counter("transfers.fraud_rejected").count()).isEqualTo(1.0);
    }

    @Test
    void incrementsNoCounterForATransientStatus() {
        Transfer transfer = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), UUID.randomUUID());
        transfer.markCompensationRequired(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, "credit leg timed out");
        when(transferRepository.save(transfer)).thenReturn(transfer);

        service.save(transfer);

        assertThat(meterRegistry.counter("transfers.completed").count()).isEqualTo(0.0);
        assertThat(meterRegistry.counter("transfers.failed").count()).isEqualTo(0.0);
    }
}
