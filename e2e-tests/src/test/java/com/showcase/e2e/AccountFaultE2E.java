package com.showcase.e2e;

import com.showcase.e2e.support.HttpResult;
import com.showcase.e2e.support.OpenedAccount;
import com.showcase.e2e.support.TestUser;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class AccountFaultE2E extends E2ETestBase {

    private static final String BREAKER = "accountService";

    // Scenario 4. Pre-validation is the first Account call, so every failure here is clean.
    // The breaker's window is shared with every earlier test in the run, so this sends until it
    // opens rather than counting to the tuned thresholds.
    @Test
    void anUnreachableAccountServiceFailsCleanlyTripsTheBreakerAndRecovers() {
        TestUser ada = users.create();
        TestUser bob = users.create();
        OpenedAccount from = bank.openAccount(ada, "EUR", "1000.00");
        OpenedAccount to = bank.openAccount(bob, "EUR", "1000.00");
        accountProxy.unreachable();

        for (int attempt = 0; attempt < 20 && !"open".equals(transferMetrics.circuitBreakerState(BREAKER)); attempt++) {
            assertCleanUnavailable(bank.transfer(ada, from, to, "1.00"));
        }
        assertThat(transferMetrics.circuitBreakerState(BREAKER)).isEqualTo("open");

        // Open: rejected without trying Account at all, so no retries and no read timeouts.
        long started = System.nanoTime();
        assertCleanUnavailable(bank.transfer(ada, from, to, "1.00"));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));

        accountProxy.reset();

        // Wait out wait-duration-in-open-state (10 s) before the next call, rather than polling
        // with transfers. A transfer that reached the breaker mid-transition could have its
        // debit refused as not permitted: an unknown outcome, left PENDING, that the sweep
        // would later complete, moving a second 1.00 behind this test's back. After the wait,
        // the next transfer's first three Account calls are the half-open trial calls (3
        // permitted), all succeed, and the breaker closes before its credit.
        sleep(Duration.ofSeconds(12));
        HttpResult recovered = bank.transfer(ada, from, to, "1.00");

        assertThat(recovered.status()).as(recovered.raw()).isEqualTo(201);
        assertThat(transferMetrics.circuitBreakerState(BREAKER)).isEqualTo("closed");
        assertThat(bank.balance(from)).isEqualByComparingTo("999.00");
        assertThat(bank.balance(to)).isEqualByComparingTo("1001.00");
    }

    // Scenario 5: the saga invariant. The debit commits at Account, Transfer never hears back.
    @Test
    void aLostDebitResponseIsLeftPendingAndSettledOnceByTheSweep() {
        TestUser ada = users.create();
        TestUser bob = users.create();
        OpenedAccount from = bank.openAccount(ada, "EUR", "1000.00");
        OpenedAccount to = bank.openAccount(bob, "EUR", "1000.00");
        accountProxy.delayDebitResponses(Duration.ofSeconds(7));

        // Three attempts x 5 s read timeout, all under the key <transferId>:debit.
        HttpResult result = bank.transfer(ada, from, to, "40.00");

        assertThat(result.status()).as(result.raw()).isEqualTo(503);
        assertThat(result.text("code")).isEqualTo("ACCOUNT_SERVICE_UNAVAILABLE");
        assertThat(result.text("transferStatus")).isEqualTo("PENDING");
        UUID transferId = result.uuid("transferId");
        accountProxy.reset();

        // The debit landed (the Gateway reads Account directly, not through the proxy), and
        // three deliveries of the same key moved the money once.
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(bank.balance(from)).isEqualByComparingTo("960.00"));

        // Stale after 70 s, then two sweep ticks: PENDING -> COMPENSATION_REQUIRED (debit
        // confirmed by replay) -> COMPLETED (credit). Never FAILED.
        await().atMost(Duration.ofSeconds(150)).pollInterval(Duration.ofSeconds(2))
                .until(() -> bank.getTransfer(ada, transferId).text("status"), "COMPLETED"::equals);

        assertThat(bank.balance(from)).isEqualByComparingTo("960.00");
        assertThat(bank.balance(to)).isEqualByComparingTo("1040.00");
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private static void assertCleanUnavailable(HttpResult result) {
        assertThat(result.status()).as(result.raw()).isEqualTo(503);
        assertThat(result.text("code")).isEqualTo("ACCOUNT_SERVICE_UNAVAILABLE");
        assertThat(result.text("transferStatus")).isEqualTo("FAILED");
    }
}
