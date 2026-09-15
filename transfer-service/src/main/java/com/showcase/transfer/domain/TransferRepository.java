package com.showcase.transfer.domain;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    /** Unbounded: used by the {@code GET /transfers?status=} listing endpoint. */
    List<Transfer> findByStatus(TransferStatus status);

    /** Limit-bounded: used by CompensationScheduler's sweeps, see its javadoc. */
    List<Transfer> findByStatus(TransferStatus status, Limit limit);

    List<Transfer> findByStatusAndCreatedAtBefore(TransferStatus status, Instant cutoff, Limit limit);
}
