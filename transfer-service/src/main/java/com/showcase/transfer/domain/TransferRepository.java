package com.showcase.transfer.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    List<Transfer> findByStatus(TransferStatus status);

    List<Transfer> findByStatusAndCreatedAtBefore(TransferStatus status, Instant cutoff);
}
