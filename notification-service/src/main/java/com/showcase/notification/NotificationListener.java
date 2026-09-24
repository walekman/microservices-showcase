package com.showcase.notification;

import com.showcase.notification.event.TransferEvent;
import com.showcase.notification.service.NotificationOutcome;
import com.showcase.notification.service.NotificationService;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes both transfer-outcome topics and stores each outcome once (NotificationService).
 * Anything thrown here goes to the error handler in KafkaErrorHandlingConfig, which decides
 * between retrying in place and dead-lettering.
 */
@Component
public class NotificationListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationListener.class);

    private final NotificationService notificationService;
    private final MeterRegistry meterRegistry;

    public NotificationListener(NotificationService notificationService, MeterRegistry meterRegistry) {
        this.notificationService = notificationService;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(topics = "${notification.topics.completed}", groupId = "${spring.kafka.consumer.group-id}")
    public void onTransferCompleted(ConsumerRecord<String, TransferEvent> record) {
        handle(record, "completed");
    }

    @KafkaListener(topics = "${notification.topics.failed}", groupId = "${spring.kafka.consumer.group-id}")
    public void onTransferFailed(ConsumerRecord<String, TransferEvent> record) {
        handle(record, "failed");
    }

    private void handle(ConsumerRecord<String, TransferEvent> record, String outcomeName) {
        NotificationOutcome outcome = notificationService.record(
                record.value(), record.topic(), record.partition(), record.offset());
        meterRegistry.counter("notification.events", "topic", record.topic(),
                "outcome", outcome.name().toLowerCase()).increment();
        if (outcome == NotificationOutcome.DUPLICATE) {
            log.info("Duplicate delivery skipped: transfer {} {}", outcomeName, record.value().transferId());
        } else {
            log.info("Notification sent: transfer {} {}", outcomeName, record.value());
        }
    }
}
