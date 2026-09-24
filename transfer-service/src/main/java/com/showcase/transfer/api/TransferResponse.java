package com.showcase.transfer.api;

import com.showcase.transfer.domain.Transfer;
import com.showcase.transfer.domain.TransferFailureCode;
import com.showcase.transfer.domain.TransferStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record TransferResponse(
        UUID id,
        UUID fromAccountId,
        UUID toAccountId,
        BigDecimal amount,
        String sourceCurrency,
        String destinationCurrency,
        BigDecimal rate,
        LocalDate rateAsOf,
        BigDecimal creditAmount,
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
                transfer.getSourceCurrency(),
                transfer.getDestinationCurrency(),
                transfer.getRate(),
                transfer.getRateAsOf(),
                // Raw, not amountToCredit(): a transfer that failed before it was priced has no credit amount.
                transfer.getCreditAmount(),
                transfer.getStatus(),
                transfer.getFailureCode(),
                transfer.getFailureReason(),
                transfer.getCreatedAt(),
                transfer.getSettledAt());
    }
}
