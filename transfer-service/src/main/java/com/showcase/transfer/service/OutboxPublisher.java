package com.showcase.transfer.service;

import com.showcase.transfer.domain.OutboxEvent;
import com.showcase.transfer.domain.OutboxEventRepository;
import com.showcase.transfer.domain.OutboxEventType;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Polls OutboxEventRepository for unpublished rows and publishes each to Kafka, mirroring
 * CompensationScheduler's SchedulingConfigurer + Limit-bounded-batch + per-row-try/catch
 * shape exactly. Single-instance deployment (docker compose, no k8s) means no distributed
 * locking is needed here, same reasoning as CompensationScheduler.
 */
@Component
public class OutboxPublisher implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxPublisherProperties properties;

    public OutboxPublisher(OutboxEventRepository outboxEventRepository, KafkaTemplate<String, String> kafkaTemplate,
                            OutboxPublisherProperties properties, MeterRegistry meterRegistry) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        meterRegistry.gauge("transfer.outbox.backlog", outboxEventRepository,
                repository -> (double) repository.countByPublishedAtIsNull());
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(this::publishPending, properties.pollInterval().toMillis());
    }

    // Package-private so OutboxPublisherIT can invoke it directly, without going through
    // the scheduler registration machinery -- same reasoning as CompensationScheduler.
    void publishPending() {
        List<OutboxEvent> pending = outboxEventRepository.findByPublishedAtIsNullOrderByCreatedAtAsc(
                Limit.of(properties.batchSize()));
        for (OutboxEvent event : pending) {
            try {
                publish(event);
            } catch (RuntimeException unexpected) {
                // One row's failure must not block the rest of this batch. The row is still
                // unpublished, so the next tick retries it -- same reasoning as
                // CompensationScheduler's loops.
                log.error("Outbox event {} failed to publish, will retry next tick", event.getId(), unexpected);
            }
        }
    }

    private void publish(OutboxEvent event) {
        String topic = topicFor(event.getEventType());
        try {
            kafkaTemplate.send(topic, event.getTransferId().toString(), event.getPayload())
                    .get(properties.publishTimeout().toMillis(), TimeUnit.MILLISECONDS);
            event.markPublished();
            outboxEventRepository.save(event);
            log.info("Outbox event {} published to {}", event.getId(), topic);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.error("Outbox event {} publish interrupted, will retry next tick", event.getId(), interrupted);
        } catch (TimeoutException | ExecutionException failed) {
            log.error("Outbox event {} failed to publish to {}, will retry next tick", event.getId(), topic, failed);
        }
    }

    private String topicFor(OutboxEventType eventType) {
        return switch (eventType) {
            case TRANSFER_COMPLETED -> properties.topics().completed();
            case TRANSFER_FAILED -> properties.topics().failed();
        };
    }
}
