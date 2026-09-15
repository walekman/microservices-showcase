package com.showcase.transfer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

// Index on (publishedAt, createdAt): OutboxPublisher's poll query
// (findByPublishedAtIsNullOrderByCreatedAtAsc) runs every poll-interval (5s default) and
// filters on publishedAt IS NULL then sorts by createdAt -- without this index it's a full
// table scan every tick, on a table that only ever grows (nothing prunes published rows;
// see docs/roadmap.md's deferred-items list). countByPublishedAtIsNull() (the backlog
// gauge, scraped on every metrics poll) benefits from the same index.
@Entity
@Table(name = "outbox_events", indexes = @Index(name = "idx_outbox_unpublished", columnList = "publishedAt, createdAt"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID transferId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32, updatable = false)
    private OutboxEventType eventType;

    @Column(nullable = false, updatable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant publishedAt;

    public OutboxEvent(UUID transferId, OutboxEventType eventType, String payload) {
        this.transferId = transferId;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = Instant.now();
    }

    public void markPublished() {
        this.publishedAt = Instant.now();
    }
}
