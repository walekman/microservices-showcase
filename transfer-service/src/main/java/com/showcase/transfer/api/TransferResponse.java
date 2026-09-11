package com.showcase.transfer.api;

import com.showcase.transfer.domain.Transfer;
import com.showcase.transfer.domain.TransferFailureCode;
import com.showcase.transfer.domain.TransferStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransferResponse(
        UUID id,
        UUID fromAccountId,
        UUID toAccountId,
        BigDecimal amount,
        TransferStatus status,
        TransferFailureCode failureCode,
        String failureReason,
        Instant createdAt,
        Instant settledAt) {

    public static TransferResponse from(Transfer transfer) {
        return new TransferResponse(
                transfer.getId(),
                transfer.getFromAccountId(),
                transfer.getToAccountId(),
                transfer.getAmount(),
                transfer.getStatus(),
                transfer.getFailureCode(),
                transfer.getFailureReason(),
                transfer.getCreatedAt(),
                transfer.getSettledAt());
    }
}
