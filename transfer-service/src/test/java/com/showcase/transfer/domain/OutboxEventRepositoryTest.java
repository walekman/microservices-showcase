package com.showcase.transfer.domain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Limit;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Testcontainers
class OutboxEventRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Test
    void findsOnlyUnpublishedEvents() {
        OutboxEvent unpublished = outboxEventRepository.saveAndFlush(
                new OutboxEvent(UUID.randomUUID(), OutboxEventType.TRANSFER_COMPLETED, "{\"a\":1}"));
        OutboxEvent published = new OutboxEvent(UUID.randomUUID(), OutboxEventType.TRANSFER_FAILED, "{\"b\":2}");
        published.markPublished();
        outboxEventRepository.saveAndFlush(published);

        List<OutboxEvent> found = outboxEventRepository.findByPublishedAtIsNullOrderByCreatedAtAsc(Limit.of(10));

        assertThat(found).extracting(OutboxEvent::getId).containsExactly(unpublished.getId());
    }

    @Test
    void countsOnlyUnpublishedEvents() {
        outboxEventRepository.saveAndFlush(new OutboxEvent(UUID.randomUUID(), OutboxEventType.TRANSFER_COMPLETED, "{}"));
        OutboxEvent published = new OutboxEvent(UUID.randomUUID(), OutboxEventType.TRANSFER_FAILED, "{}");
        published.markPublished();
        outboxEventRepository.saveAndFlush(published);

        assertThat(outboxEventRepository.countByPublishedAtIsNull()).isEqualTo(1);
    }
}
