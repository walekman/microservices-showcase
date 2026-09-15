// transfer-service/src/test/java/com/showcase/transfer/service/TransferOutboxServiceTest.java
package com.showcase.transfer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.showcase.transfer.domain.OutboxEventRepository;
import com.showcase.transfer.domain.Transfer;
import com.showcase.transfer.domain.TransferFailureCode;
import com.showcase.transfer.domain.TransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferOutboxServiceTest {

    @Mock
    private TransferRepository transferRepository;
    @Mock
    private OutboxEventRepository outboxEventRepository;

    private TransferOutboxService service;

    @BeforeEach
    void setUp() {
        // A plain ObjectMapper does not know how to serialize java.time.Instant without this
        // module -- Spring's auto-configured bean has it registered already, but a
        // hand-built one in a unit test needs it explicitly, or toPayload() throws.
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new TransferOutboxService(transferRepository, outboxEventRepository, objectMapper);
    }

    @Test
    void writesAnOutboxRowForACompletedTransfer() {
        Transfer transfer = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"));
        transfer.markCompleted();
        when(transferRepository.save(transfer)).thenReturn(transfer);

        service.save(transfer);

        verify(outboxEventRepository).save(any());
    }

    @Test
    void writesNoOutboxRowForATransientStatus() {
        Transfer transfer = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"));
        transfer.markCompensationRequired(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, "credit leg timed out");
        when(transferRepository.save(transfer)).thenReturn(transfer);

        service.save(transfer);

        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    void writesAnOutboxRowForEachTerminalStatus() {
        Transfer completed = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"));
        completed.markCompleted();
        Transfer failed = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"));
        failed.markFailed(TransferFailureCode.INSUFFICIENT_FUNDS, "not enough money");
        when(transferRepository.save(completed)).thenReturn(completed);
        when(transferRepository.save(failed)).thenReturn(failed);

        service.save(completed);
        service.save(failed);

        verify(outboxEventRepository, org.mockito.Mockito.times(2)).save(any());
    }
}
