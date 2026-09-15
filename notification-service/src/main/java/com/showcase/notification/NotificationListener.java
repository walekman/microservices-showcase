package com.showcase.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Stateless per spec §2: consumes both transfer-outcome topics and logs. No persistence. */
@Component
public class NotificationListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationListener.class);

    @KafkaListener(topics = "${notification.topics.completed}", groupId = "${spring.kafka.consumer.group-id}")
    public void onTransferCompleted(String payload) {
        log.info("Notification sent: transfer completed {}", payload);
    }

    @KafkaListener(topics = "${notification.topics.failed}", groupId = "${spring.kafka.consumer.group-id}")
    public void onTransferFailed(String payload) {
        log.info("Notification sent: transfer failed {}", payload);
    }
}
