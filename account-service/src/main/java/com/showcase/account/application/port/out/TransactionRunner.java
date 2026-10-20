package com.showcase.account.application.port.out;

import java.util.function.Supplier;

/**
 * Runs work in one transaction, so the application layer can state its transaction boundaries
 * without depending on a framework. A RuntimeException thrown by the work rolls back everything
 * it wrote.
 */
public interface TransactionRunner {

    <T> T inTransaction(Supplier<T> work);

    <T> T readOnly(Supplier<T> work);
}
