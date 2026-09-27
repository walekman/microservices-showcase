package com.showcase.e2e;

import com.showcase.e2e.support.HttpResult;
import com.showcase.e2e.support.OpenedAccount;
import com.showcase.e2e.support.TestUser;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class FraudScreeningE2E extends E2ETestBase {

    // Scenario 2: screened before the debit, so nothing moves.
    @Test
    void aBlockedSourceFailsCleanly() {
        TestUser ada = users.create();
        TestUser bob = users.create();
        OpenedAccount from = bank.openAccount(ada, "EUR", "1000.00");
        OpenedAccount to = bank.openAccount(bob, "EUR", "1000.00");
        fraudAdmin.block(from);

        HttpResult result = bank.transfer(ada, from, to, "40.00");

        assertThat(result.status()).as(result.raw()).isEqualTo(422);
        assertThat(result.text("code")).isEqualTo("SOURCE_ACCOUNT_BLOCKED");
        assertThat(result.text("transferStatus")).isEqualTo("FAILED");
        HttpResult stored = bank.getTransfer(ada, result.uuid("transferId"));
        assertThat(stored.text("status")).isEqualTo("FAILED");
        assertThat(stored.text("failureCode")).isEqualTo("SOURCE_ACCOUNT_BLOCKED");
        assertThat(bank.balance(from)).isEqualByComparingTo("1000.00");
        assertThat(bank.balance(to)).isEqualByComparingTo("1000.00");
    }

    // Scenario 3: screened after the debit, so money moves and CompensationScheduler puts it back.
    @Test
    void aBlockedDestinationIsDebitedThenCompensated() {
        TestUser ada = users.create();
        TestUser bob = users.create();
        OpenedAccount from = bank.openAccount(ada, "EUR", "1000.00");
        OpenedAccount to = bank.openAccount(bob, "EUR", "1000.00");
        fraudAdmin.block(to);

        HttpResult result = bank.transfer(ada, from, to, "40.00");

        assertThat(result.status()).as(result.raw()).isEqualTo(500);
        assertThat(result.text("code")).isEqualTo("COMPENSATION_REQUIRED");
        assertThat(result.text("transferStatus")).isEqualTo("COMPENSATION_REQUIRED");
        UUID transferId = result.uuid("transferId");
        assertThat(bank.getTransfer(ada, transferId).text("failureCode")).isEqualTo("DESTINATION_ACCOUNT_BLOCKED");

        // The e2e override sweeps every 5 s. The destination stays blocked, so the drain
        // compensates the source rather than retrying the credit.
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1))
                .until(() -> bank.getTransfer(ada, transferId).text("status"), "COMPENSATED"::equals);

        assertThat(bank.balance(from)).isEqualByComparingTo("1000.00");
        assertThat(bank.balance(to)).isEqualByComparingTo("1000.00");
    }
}
