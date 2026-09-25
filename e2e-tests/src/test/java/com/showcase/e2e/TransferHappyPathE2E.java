package com.showcase.e2e;

import com.showcase.e2e.support.HttpResult;
import com.showcase.e2e.support.OpenedAccount;
import com.showcase.e2e.support.TestUser;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TransferHappyPathE2E extends E2ETestBase {

    // Scenario 1
    @Test
    void happyPathMovesTheMoneyExactlyOnceEvenWhenTheRequestIsRepeated() {
        TestUser ada = users.create();
        TestUser bob = users.create();
        OpenedAccount from = bank.openAccount(ada, "EUR", "1000.00");
        OpenedAccount to = bank.openAccount(bob, "EUR", "1000.00");
        String idempotencyKey = UUID.randomUUID().toString();

        HttpResult first = bank.transfer(ada, from, to, "40.00", idempotencyKey);

        assertThat(first.status()).as(first.raw()).isEqualTo(201);
        assertThat(first.text("status")).isEqualTo("COMPLETED");
        UUID transferId = first.uuid("id");
        assertThat(bank.balance(from)).isEqualByComparingTo("960.00");
        assertThat(bank.balance(to)).isEqualByComparingTo("1040.00");
        assertThat(bank.getTransfer(ada, transferId).text("status")).isEqualTo("COMPLETED");

        HttpResult repeated = bank.transfer(ada, from, to, "40.00", idempotencyKey);

        assertThat(repeated.status()).as(repeated.raw()).isEqualTo(201);
        assertThat(repeated.uuid("id")).isEqualTo(transferId);
        assertThat(bank.balance(from)).isEqualByComparingTo("960.00");
        assertThat(bank.balance(to)).isEqualByComparingTo("1040.00");
    }

    // Scenario 6: rates come from docker/e2e/fx-provider/mappings/latest-eur.json.
    @Test
    void crossCurrencyTransferCreditsTheAmountAtTheLockedRate() {
        TestUser ada = users.create();
        TestUser bob = users.create();
        OpenedAccount euros = bank.openAccount(ada, "EUR", "1000.00");
        OpenedAccount zlotys = bank.openAccount(bob, "PLN", "1000.00");

        HttpResult result = bank.transfer(ada, euros, zlotys, "100.00");

        assertThat(result.status()).as(result.raw()).isEqualTo(201);
        assertThat(result.text("status")).isEqualTo("COMPLETED");
        assertThat(result.text("sourceCurrency")).isEqualTo("EUR");
        assertThat(result.text("destinationCurrency")).isEqualTo("PLN");
        assertThat(result.decimal("rate")).isEqualByComparingTo("4.2537");
        assertThat(result.text("rateAsOf")).isEqualTo("2026-09-24");
        // 100.00 x 4.2537 = 425.37 exactly, so HALF_EVEN rounding has nothing to do here.
        assertThat(result.decimal("creditAmount")).isEqualByComparingTo("425.37");
        assertThat(bank.balance(euros)).isEqualByComparingTo("900.00");
        assertThat(bank.balance(zlotys)).isEqualByComparingTo("1425.37");
    }
}
