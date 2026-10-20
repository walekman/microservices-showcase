package com.showcase.account.application.port.out;

import com.showcase.account.domain.Account;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountStore {

    Optional<Account> findById(UUID id);

    List<Account> findAll();

    List<Account> findAllByOwnerId(UUID ownerId);

    boolean existsByOwnerId(UUID ownerId);

    /**
     * Stores a new account and returns it with its assigned id. Throws
     * AccountAlreadyExistsException if its owner already has one, including when another
     * request created it concurrently -- the store, not a prior existsByOwnerId check, is what
     * enforces one account per owner.
     */
    Account insert(Account account);

    /**
     * Writes an existing account's new balance immediately (not at commit). Throws
     * AccountConcurrentlyModifiedException if the stored account changed since this copy was
     * read.
     */
    Account save(Account account);
}
