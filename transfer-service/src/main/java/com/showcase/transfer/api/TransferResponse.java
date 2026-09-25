package com.showcase.transfer.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.showcase.transfer.domain.Transfer;
import com.showcase.transfer.domain.TransferFailureCode;
import com.showcase.transfer.domain.TransferStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
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
        Instant settledAt,
        // Relative to a caller, so only GET /transfers/mine sets it; omitted everywhere else.
        @JsonInclude(JsonInclude.Include.NON_NULL) Direction direction) {

    public enum Direction { OUTGOING, INCOMING }

    /** For GET /transfers/mine: the caller started it (outgoing), or it paid into their account (incoming). */
    public static TransferResponse forCaller(Transfer transfer, UUID callerId) {
        return from(transfer, Objects.equals(transfer.getInitiatorId(), callerId) ? Direction.OUTGOING : Direction.INCOMING);
    }

    public static TransferResponse from(Transfer transfer) {
        return from(transfer, null);
    }

    private static TransferResponse from(Transfer transfer, Direction direction) {
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
                transfer.getSettledAt(),
                direction);
    }
}
