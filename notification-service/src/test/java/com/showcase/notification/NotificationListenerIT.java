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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class NotificationListenerIT {

    @Container
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.0");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private KafkaTemplate<Object, Object> kafkaTemplate;

    @Test
    void logsNotificationSentOnTransferCompleted(CapturedOutput output) throws InterruptedException {
        String transferId = UUID.randomUUID().toString();
        kafkaTemplate.send("transfer.completed", transferId,
                "{\"transferId\":\"" + transferId + "\",\"status\":\"COMPLETED\"}");

        awaitLogLine(output, "Notification sent: transfer completed");
    }

    @Test
    void logsNotificationSentOnTransferFailed(CapturedOutput output) throws InterruptedException {
        String transferId = UUID.randomUUID().toString();
        kafkaTemplate.send("transfer.failed", transferId,
                "{\"transferId\":\"" + transferId + "\",\"status\":\"FAILED\"}");

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
