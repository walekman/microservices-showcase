package com.showcase.transfer.domain;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    /** Unbounded: used by the {@code GET /transfers?status=} listing endpoint. */
    List<Transfer> findByStatus(TransferStatus status);

    /** Limit-bounded: used by CompensationScheduler's sweeps, see its javadoc. */
    List<Transfer> findByStatus(TransferStatus status, Limit limit);

    List<Transfer> findByStatusAndCreatedAtBefore(TransferStatus status, Instant cutoff, Limit limit);

    List<Transfer> findByInitiatorId(UUID initiatorId);

    List<Transfer> findByInitiatorIdAndStatus(UUID initiatorId, TransferStatus status);

    /** The incoming half of GET /transfers/mine: transfers into any of the caller's accounts. */
    List<Transfer> findByToAccountIdInAndStatus(Collection<UUID> toAccountIds, TransferStatus status);

    /** Idempotent POST /transfers: the transfer an earlier request with this key created, if any. */
    Optional<Transfer> findByInitiatorIdAndIdempotencyKey(UUID initiatorId, String idempotencyKey);
}
