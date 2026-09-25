package com.showcase.fraud.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

public interface BlockedAccountRepository extends JpaRepository<BlockedAccount, UUID> {

    /**
     * Returns 1 if this call blocked the account, 0 if it was already blocked. ON CONFLICT makes
     * two concurrent blocks of the same account both succeed, with no constraint violation to
     * catch, and leaves the first blockedAt in place.
     */
    @Modifying
    @Transactional
    @Query(value = "INSERT INTO blocked_accounts (account_id, blocked_at) VALUES (:accountId, :blockedAt) "
            + "ON CONFLICT (account_id) DO NOTHING", nativeQuery = true)
    int blockIfAbsent(@Param("accountId") UUID accountId, @Param("blockedAt") Instant blockedAt);

    /** Returns 1 if the account was blocked, 0 if it was not. */
    @Modifying
    @Transactional
    @Query("DELETE FROM BlockedAccount b WHERE b.accountId = :accountId")
    int unblock(@Param("accountId") UUID accountId);
}
