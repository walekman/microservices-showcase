package com.showcase.transfer.domain;

/** Why a transfer did not complete, in Transfer Service vocabulary. */
public enum TransferFailureCode {
    ACCOUNT_NOT_FOUND,
    INSUFFICIENT_FUNDS,
    CONCURRENT_MODIFICATION,
    ACCOUNT_SERVICE_UNAVAILABLE,
    UNEXPECTED_ERROR;

    /** Translates a code from Account Service into this service vocabulary. */
    public static TransferFailureCode fromAccountCode(String accountCode) {
        return switch (accountCode) {
            case "ACCOUNT_NOT_FOUND" -> ACCOUNT_NOT_FOUND;
            case "INSUFFICIENT_FUNDS" -> INSUFFICIENT_FUNDS;
            case "CONCURRENT_MODIFICATION" -> CONCURRENT_MODIFICATION;
            default -> UNEXPECTED_ERROR;
        };
    }
}
