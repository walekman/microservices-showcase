// transfer-service/src/main/java/com/showcase/transfer/service/TransferSaveService.java
package com.showcase.transfer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.showcase.transfer.domain.OutboxEvent;
import com.showcase.transfer.domain.OutboxEventRepository;
import com.showcase.transfer.domain.OutboxEventType;
import com.showcase.transfer.domain.Transfer;
import com.showcase.transfer.domain.TransferFailureCode;
import com.showcase.transfer.domain.TransferRepository;
import com.showcase.transfer.domain.TransferStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The only place a Transfer is persisted after a mark*() status transition. Centralizing
 * it here means no call site (TransferService, CompensationScheduler) has to know which
 * statuses are "outbox-worthy" -- that decision lives in one place,
 * OutboxEventType.forStatus(). The @Transactional here is narrow: one repository save
 * plus, at most, one insert -- no HTTP calls inside it -- so it does not reopen
 * TransferService's "the saga itself must not be @Transactional" rule; see
 * docs/phase-2-transfer-service-saga.md's Design Decisions and this phase's own.
 */
@Service
public class TransferSaveService {

    private final TransferRepository transferRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public TransferSaveService(TransferRepository transferRepository,
                                  OutboxEventRepository outboxEventRepository,
                                  ObjectMapper objectMapper) {
        this.transferRepository = transferRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Transfer save(Transfer transfer) {
        Transfer saved = transferRepository.save(transfer);
        OutboxEventType eventType = OutboxEventType.forStatus(saved.getStatus());
        if (eventType != null) {
            outboxEventRepository.save(new OutboxEvent(saved.getId(), eventType, toPayload(saved)));
        }
        return saved;
    }

    private String toPayload(Transfer transfer) {
        try {
            return objectMapper.writeValueAsString(new TransferEventPayload(
                    transfer.getId(), transfer.getFromAccountId(), transfer.getToAccountId(),
                    transfer.getAmount(), transfer.getStatus(), transfer.getFailureCode(),
                    transfer.getFailureReason(), transfer.getSettledAt()));
        } catch (JsonProcessingException impossible) {
            // Every field here is a UUID/BigDecimal/enum/String/Instant -- Jackson has no
            // way to fail serializing this record once JavaTimeModule is registered (it is,
            // on the Spring-managed ObjectMapper this class is given). Wrapped rather than
            // declared throws so a save() nobody expects to fail doesn't force a checked
            // exception on every caller for a failure mode that cannot happen.
            throw new IllegalStateException(
                    "Failed to serialize outbox payload for transfer " + transfer.getId(), impossible);
        }
    }

    private record TransferEventPayload(UUID transferId, UUID fromAccountId, UUID toAccountId, BigDecimal amount,
                                         TransferStatus status, TransferFailureCode failureCode,
                                         String failureReason, Instant settledAt) {
    }
}
