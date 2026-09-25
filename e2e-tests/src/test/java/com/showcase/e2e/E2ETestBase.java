package com.showcase.e2e;

import com.showcase.e2e.support.AccountProxy;
import com.showcase.e2e.support.Bank;
import com.showcase.e2e.support.E2EStack;
import com.showcase.e2e.support.FraudAdmin;
import com.showcase.e2e.support.TestUsers;
import com.showcase.e2e.support.TransferMetrics;
import org.junit.jupiter.api.AfterEach;

/** Every E2E class shares the one stack; fixtures are per test instance and hold no state across tests. */
abstract class E2ETestBase {

    protected static final E2EStack STACK = E2EStack.get();

    protected final TestUsers users = new TestUsers(STACK.keycloak());
    protected final Bank bank = new Bank(STACK.gateway());
    protected final FraudAdmin fraudAdmin = new FraudAdmin(STACK.fraud(), users);
    protected final AccountProxy accountProxy = new AccountProxy(STACK.accountProxy());
    protected final TransferMetrics transferMetrics = new TransferMetrics(STACK.transfer());

    @AfterEach
    void cleanUp() {
        accountProxy.reset();
        fraudAdmin.unblockAll();
    }
}
