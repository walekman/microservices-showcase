package com.showcase.notification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class NotificationListenerIT {

    @Container
    static ConfluentKafkaContainer kafka = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    private KafkaTemplate<Object, Object> kafkaTemplate;

    @Test
    void logsNotificationSentOnTransferCompleted(CapturedOutput output) throws InterruptedException {
        kafkaTemplate.send("transfer.completed", "t-1", "{\"transferId\":\"t-1\",\"status\":\"COMPLETED\"}");

        awaitLogLine(output, "Notification sent: transfer completed");
    }

    @Test
    void logsNotificationSentOnTransferFailed(CapturedOutput output) throws InterruptedException {
        kafkaTemplate.send("transfer.failed", "t-2", "{\"transferId\":\"t-2\",\"status\":\"FAILED\"}");

        awaitLogLine(output, "Notification sent: transfer failed");
    }

    private void awaitLogLine(CapturedOutput output, String expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!output.getOut().contains(expected) && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        assertThat(output).contains(expected);
    }
}
