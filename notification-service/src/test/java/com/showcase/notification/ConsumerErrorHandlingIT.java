package com.showcase.notification;

import com.showcase.notification.domain.NotificationRepository;
import com.showcase.notification.event.TransferStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * One test per row of the behaviour table in
 * docs/phase-11-notification-persistence-error-handling.md: what is retried in place, what is
 * dead-lettered, and that neither blocks the records behind it once resolved.
 */
@SpringBootTest(properties = {
        "notification.retry.initial-interval=200ms",
        "notification.retry.max-interval=1s"
})
@Testcontainers
class ConsumerErrorHandlingIT {

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
    @Autowired
    private NotificationRepository repository;
    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void anUndeserializablePayloadIsDeadLetteredVerbatimAndTheNextRecordIsStillProcessed() {
        String poisonKey = "poison-" + UUID.randomUUID();
        UUID next = UUID.randomUUID();

        kafkaTemplate.send("transfer.completed", poisonKey, "this is not json");
        kafkaTemplate.send("transfer.completed", next.toString(), event(next, "COMPLETED"));

        // No poison-pill loop: the record behind the bad one on the same partition is stored.
        await().atMost(Duration.ofSeconds(20)).until(() -> repository.findByTransferId(next).isPresent());

        ConsumerRecord<String, byte[]> deadLetter = awaitDeadLetter("transfer.completed-dlt", poisonKey);
        assertThat(new String(deadLetter.value(), StandardCharsets.UTF_8)).isEqualTo("this is not json");
        assertThat(header(deadLetter, KafkaHeaders.DLT_EXCEPTION_FQCN))
                .isEqualTo(DeserializationException.class.getName());
        assertThat(header(deadLetter, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo("transfer.completed");
        await().atMost(Duration.ofSeconds(20))
                .until(() -> deadLettered("transfer.completed-dlt", "DeserializationException") >= 1);
    }

    @Test
    void aStatusTransferNeverPublishesIsAnUndeserializablePayload() {
        UUID transferId = UUID.randomUUID();

        kafkaTemplate.send("transfer.failed", transferId.toString(), event(transferId, "PENDING"));

        ConsumerRecord<String, byte[]> deadLetter = awaitDeadLetter("transfer.failed-dlt", transferId.toString());
        assertThat(header(deadLetter, KafkaHeaders.DLT_EXCEPTION_FQCN))
                .isEqualTo(DeserializationException.class.getName());
        assertThat(repository.findByTransferId(transferId)).isEmpty();
    }

    @Test
    void anEventWithoutATransferIdIsDeadLetteredOnTheFirstAttempt() {
        String key = "no-id-" + UUID.randomUUID();
        double failuresBefore = deliveryFailures("InvalidEventException");

        kafkaTemplate.send("transfer.completed", key, "{\"status\":\"COMPLETED\",\"amount\":1.00}");

        ConsumerRecord<String, byte[]> deadLetter = awaitDeadLetter("transfer.completed-dlt", key);
        assertThat(header(deadLetter, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN)).endsWith(".InvalidEventException");
        // Not retryable: exactly one failed delivery before it was dead-lettered.
        assertThat(deliveryFailures("InvalidEventException")).isEqualTo(failuresBefore + 1);
        await().atMost(Duration.ofSeconds(20))
                .until(() -> deadLettered("transfer.completed-dlt", "InvalidEventException") >= 1);
    }

    @Test
    void aConflictingSecondOutcomeIsDeadLetteredAndTheStoredRowIsKept() {
        UUID transferId = UUID.randomUUID();

        kafkaTemplate.send("transfer.completed", transferId.toString(), event(transferId, "COMPLETED"));
        await().atMost(Duration.ofSeconds(20)).until(() -> repository.findByTransferId(transferId).isPresent());
        kafkaTemplate.send("transfer.failed", transferId.toString(), event(transferId, "FAILED"));

        ConsumerRecord<String, byte[]> deadLetter = awaitDeadLetter("transfer.failed-dlt", transferId.toString());
        assertThat(header(deadLetter, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN))
                .endsWith(".ConflictingEventException");
        assertThat(repository.findByTransferId(transferId)).get()
                .extracting(n -> n.getStatus()).isEqualTo(TransferStatus.COMPLETED);
    }

    @Test
    void aDatabaseOutageIsRetriedInPlaceUntilTheDatabaseIsBackAndNothingIsDeadLettered() {
        UUID transferId = UUID.randomUUID();
        double deadLetteredBefore = deadLettered("transfer.completed-dlt", null);
        double failuresBefore = allDeliveryFailures();

        var docker = postgres.getDockerClient();
        docker.pauseContainerCmd(postgres.getContainerId()).exec();
        try {
            kafkaTemplate.send("transfer.completed", transferId.toString(), event(transferId, "COMPLETED"));
            // Several attempts, each failing on the unreachable DB -- and none dead-lettered.
            await().atMost(Duration.ofSeconds(60)).until(() -> allDeliveryFailures() >= failuresBefore + 3);
            assertThat(deadLettered("transfer.completed-dlt", null)).isEqualTo(deadLetteredBefore);
        } finally {
            docker.unpauseContainerCmd(postgres.getContainerId()).exec();
        }

        await().atMost(Duration.ofSeconds(60)).until(() -> repository.findByTransferId(transferId).isPresent());
        assertThat(deadLettered("transfer.completed-dlt", null)).isEqualTo(deadLetteredBefore);
    }

    private static String event(UUID transferId, String status) {
        return "{\"transferId\":\"%s\",\"status\":\"%s\",\"amount\":10.00}".formatted(transferId, status);
    }

    private ConsumerRecord<String, byte[]> awaitDeadLetter(String topic, String key) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (KafkaConsumer<String, byte[]> consumer =
                     new KafkaConsumer<>(config, new StringDeserializer(), new ByteArrayDeserializer())) {
            consumer.subscribe(List.of(topic));
            AtomicReference<ConsumerRecord<String, byte[]>> found = new AtomicReference<>();
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (key.equals(r.key())) {
                        found.set(r);
                    }
                });
                return found.get() != null;
            });
            return found.get();
        }
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private double deadLettered(String topic, String reason) {
        var search = meterRegistry.find("notification.dead.lettered").tag("topic", topic);
        if (reason != null) {
            search = search.tag("reason", reason);
        }
        return search.counters().stream().mapToDouble(Counter::count).sum();
    }

    private double deliveryFailures(String exception) {
        return meterRegistry.find("notification.delivery.failures").tag("exception", exception)
                .counters().stream().mapToDouble(Counter::count).sum();
    }

    private double allDeliveryFailures() {
        return meterRegistry.find("notification.delivery.failures")
                .counters().stream().mapToDouble(Counter::count).sum();
    }
}
