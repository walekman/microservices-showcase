package com.showcase.transfer.service;

import com.showcase.transfer.domain.OutboxEvent;
import com.showcase.transfer.domain.OutboxEventRepository;
import com.showcase.transfer.domain.OutboxEventType;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class OutboxPublisherIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    // @ServiceConnection on ConfluentKafkaContainer throws ConnectionDetailsNotFoundException
    // on Spring Boot 3.3.4 -- found live during Task 3's implementation. Wire the bootstrap
    // address manually instead; Spring Boot's own Kafka autoconfiguration takes it from there.
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

    @Autowired
    private OutboxPublisherProperties outboxPublisherProperties;

    @Test
    void publishesAnUnpublishedEventAndMarksItPublished() {
        UUID transferId = UUID.randomUUID();
        String payload = "{\"status\":\"COMPLETED\"}";
        OutboxEvent event = outboxEventRepository.saveAndFlush(
                new OutboxEvent(transferId, OutboxEventType.TRANSFER_COMPLETED, payload));

        outboxPublisher.publishPending();

        OutboxEvent reloaded = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(reloaded.getPublishedAt()).isNotNull();

        // Only asserting publishedAt is non-null does not prove anything about WHERE the
        // message went or what it carried: because the test broker auto-creates topics,
        // topicFor() routing to the wrong topic string would still leave this test green.
        // Attach a real consumer to the topic OutboxPublisherProperties says TRANSFER_COMPLETED
        // routes to, and prove the key/value contract directly -- this is the one thing nothing
        // else in the suite protects.
        String topic = outboxPublisherProperties.topics().completed();
        var consumerProps = KafkaTestUtils.consumerProps(kafka.getBootstrapServers(), "outbox-publisher-it", "true");
        // consumerProps() defaults to IntegerDeserializer for the key -- OutboxPublisher's
        // producer key is the transfer id as a String (see topicFor()/publish()), so this
        // must be overridden or the consumer throws deserializing the first record.
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (Consumer<String, String> consumer = new KafkaConsumer<>(consumerProps)) {
            consumer.subscribe(List.of(topic));
            ConsumerRecord<String, String> record = KafkaTestUtils.getSingleRecord(consumer, topic, Duration.ofSeconds(10));

            assertThat(record.key()).isEqualTo(transferId.toString());
            assertThat(record.value()).isEqualTo(payload);
        }
    }

    @Test
    void leavesAnAlreadyPublishedEventAlone() {
        OutboxEvent event = new OutboxEvent(UUID.randomUUID(), OutboxEventType.TRANSFER_FAILED, "{}");
        event.markPublished();
        outboxEventRepository.saveAndFlush(event);
        // Reload rather than using the in-memory instance's timestamp: without a surrounding
        // transaction (deliberately -- see OutboxPublisher's own javadoc), this test's second
        // read is a genuinely separate persistence context, so the in-memory Instant.now() and
        // the database's stored microsecond-precision value are not the same object.
        var publishedAtBefore = outboxEventRepository.findById(event.getId()).orElseThrow().getPublishedAt();

        outboxPublisher.publishPending();

        OutboxEvent reloaded = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(reloaded.getPublishedAt()).isEqualTo(publishedAtBefore);
    }
}
