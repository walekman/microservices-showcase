package com.showcase.transfer.domain;

/** Why a transfer did not complete, in Transfer Service vocabulary. */
public enum TransferFailureCode {
    ACCOUNT_NOT_FOUND,
    INSUFFICIENT_FUNDS,
    CONCURRENT_MODIFICATION,
    ACCOUNT_SERVICE_UNAVAILABLE,
    UNEXPECTED_ERROR;

    /**
     * Translates a code from Account Service into this service vocabulary.
     *
     * <p>A null code is not hypothetical: a well-formed problem document that simply omits
     * {@code code} (a proxy or gateway between the services can produce one) binds to a null
     * here. Failing with an NPE while handling a downstream failure would turn a transfer that
     * should be recorded as FAILED into a 500 with no record of why, so a null maps to
     * {@link #UNEXPECTED_ERROR} like any other unrecognised code.
     */
    public static TransferFailureCode fromAccountCode(String accountCode) {
        if (accountCode == null) {
            return UNEXPECTED_ERROR;
        }
        return switch (accountCode) {
            case "ACCOUNT_NOT_FOUND" -> ACCOUNT_NOT_FOUND;
            case "INSUFFICIENT_FUNDS" -> INSUFFICIENT_FUNDS;
            case "CONCURRENT_MODIFICATION" -> CONCURRENT_MODIFICATION;
            default -> UNEXPECTED_ERROR;
        };
    }
}
