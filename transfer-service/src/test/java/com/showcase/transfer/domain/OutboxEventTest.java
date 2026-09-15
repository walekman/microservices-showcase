package com.showcase.transfer.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxEventTest {

    @Test
    void newEventIsUnpublished() {
        OutboxEvent event = new OutboxEvent(UUID.randomUUID(), OutboxEventType.TRANSFER_COMPLETED, "{}");

        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getCreatedAt()).isNotNull();
        assertThat(event.getEventType()).isEqualTo(OutboxEventType.TRANSFER_COMPLETED);
        assertThat(event.getPayload()).isEqualTo("{}");
    }

    @Test
    void markPublishedSetsTimestamp() {
        OutboxEvent event = new OutboxEvent(UUID.randomUUID(), OutboxEventType.TRANSFER_COMPLETED, "{}");

        event.markPublished();

        assertThat(event.getPublishedAt()).isNotNull();
    }
}
