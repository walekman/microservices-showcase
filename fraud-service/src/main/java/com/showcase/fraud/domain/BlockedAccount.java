package com.showcase.fraud.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * One blocklisted account. Rows are written only through BlockedAccountRepository's two
 * queries, never through save(): the id is assigned rather than generated, so save() would
 * merge (a SELECT, then an UPDATE of blockedAt) instead of inserting.
 */
@Entity
@Table(name = "blocked_accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BlockedAccount {

    @Id
    private UUID accountId;

    @Column(nullable = false, updatable = false)
    private Instant blockedAt;
}
