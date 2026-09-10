package com.showcase.transfer.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TransferFailureCodeTest {

    @Test
    void mapsKnownAccountServiceCodes() {
        assertThat(TransferFailureCode.fromAccountCode("ACCOUNT_NOT_FOUND"))
                .isEqualTo(TransferFailureCode.ACCOUNT_NOT_FOUND);
        assertThat(TransferFailureCode.fromAccountCode("INSUFFICIENT_FUNDS"))
                .isEqualTo(TransferFailureCode.INSUFFICIENT_FUNDS);
        assertThat(TransferFailureCode.fromAccountCode("CONCURRENT_MODIFICATION"))
                .isEqualTo(TransferFailureCode.CONCURRENT_MODIFICATION);
    }

    @Test
    void mapsAnUnrecognisedCodeToUnexpectedError() {
        assertThat(TransferFailureCode.fromAccountCode("SOMETHING_ELSE"))
                .isEqualTo(TransferFailureCode.UNEXPECTED_ERROR);
    }

    @Test
    void mapsAMissingCodeToUnexpectedErrorRatherThanThrowing() {
        assertThat(TransferFailureCode.fromAccountCode(null))
                .isEqualTo(TransferFailureCode.UNEXPECTED_ERROR);
    }
}
