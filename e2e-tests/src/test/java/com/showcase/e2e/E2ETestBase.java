package com.showcase.e2e;

import com.showcase.e2e.support.Bank;
import com.showcase.e2e.support.E2EStack;
import com.showcase.e2e.support.TestUsers;

/** Every E2E class shares the one stack; fixtures are per test instance and hold no state across tests. */
abstract class E2ETestBase {

    protected static final E2EStack STACK = E2EStack.get();

    protected final TestUsers users = new TestUsers(STACK.keycloak());
    protected final Bank bank = new Bank(STACK.gateway());
}
