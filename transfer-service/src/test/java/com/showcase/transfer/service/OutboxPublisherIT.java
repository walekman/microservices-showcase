package com.showcase.transfer.service;

import com.showcase.transfer.domain.OutboxEvent;
import com.showcase.transfer.domain.OutboxEventRepository;
import com.showcase.transfer.domain.OutboxEventType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@Transactional
class OutboxPublisherIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static ConfluentKafkaContainer kafka = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Test
    void publishesAnUnpublishedEventAndMarksItPublished() {
        OutboxEvent event = outboxEventRepository.saveAndFlush(
                new OutboxEvent(UUID.randomUUID(), OutboxEventType.TRANSFER_COMPLETED, "{\"status\":\"COMPLETED\"}"));

        outboxPublisher.publishPending();

        OutboxEvent reloaded = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(reloaded.getPublishedAt()).isNotNull();
    }

    @Test
    void leavesAnAlreadyPublishedEventAlone() {
        OutboxEvent event = new OutboxEvent(UUID.randomUUID(), OutboxEventType.TRANSFER_FAILED, "{}");
        event.markPublished();
        OutboxEvent saved = outboxEventRepository.saveAndFlush(event);
        var publishedAtBefore = saved.getPublishedAt();

        outboxPublisher.publishPending();

        OutboxEvent reloaded = outboxEventRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getPublishedAt()).isEqualTo(publishedAtBefore);
    }
}
