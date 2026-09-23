package com.showcase.transfer.service;

import com.showcase.transfer.domain.OutboxEvent;
import com.showcase.transfer.domain.OutboxEventRepository;
import com.showcase.transfer.domain.OutboxEventType;
import com.showcase.transfer.client.StubServiceTokenTestConfig;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// @AutoConfigureObservability: @SpringBootTest disables real tracing/metrics export by
// default (Spring Boot's own ObservabilityContextCustomizerFactory adds
// management.tracing.enabled=false as a test-only property source, overriding whatever
// application.yml says, regardless of precedence tricks -- confirmed live via the
// condition-evaluation report, including with management.tracing.enabled=true set as a
// same-JVM unforked system property, which still had no effect). Needed here, and only
// here in this class, because publishedRecordCarriesTraceparentHeaderWhenTheOutboxEventHasTraceContext
// asserts on real wire-level header propagation, not just a mocked call or a bean's type.
@SpringBootTest
@AutoConfigureObservability
@Testcontainers
@Import(StubServiceTokenTestConfig.class)
class OutboxPublisherIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    // @ServiceConnection on the Kafka container threw ConnectionDetailsNotFoundException
    // on Spring Boot 3.3.4 -- found live during Task 3's implementation. Wire the bootstrap
    // address manually instead; Spring Boot's own Kafka autoconfiguration takes it from there.
    @Container
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.0");

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        // OutboxPublisher.publishPending() has no claim/lock step (single-instance deployment,
        // see its class javadoc) -- it's only safe against ONE caller at a time. This class's
        // tests invoke it directly (see the comment on the package-private method itself), but
        // @SpringBootTest also boots the real, live @Scheduled tick from OutboxPublisher's own
        // SchedulingConfigurer registration at the default 5s poll-interval. When that tick
        // landed inside a test's own direct call, both read the same unpublished row before
        // either had marked it published and both published it, producing two Kafka records for
        // one event -- confirmed by reproducing it with a 50ms poll-interval, which showed two
        // "published to" log lines for the same event id, one on the scheduling thread and one
        // on the test thread. Pinning the interval far past this test class's runtime removes
        // the second caller entirely, leaving the direct call as the only publisher.
        registry.add("transfer.outbox.poll-interval", () -> "1h");
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

    @Test
    void publishedRecordCarriesTraceparentHeaderWhenTheOutboxEventHasTraceContext() {
        // TRANSFER_FAILED, not TRANSFER_COMPLETED -- publishesAnUnpublishedEventAndMarksItPublished
        // above already publishes a real record to the TRANSFER_COMPLETED topic in this same
        // class. A fresh consumer group here defaults to reading from the earliest offset, so
        // reusing that topic would pick up that other test's leftover record too and fail with
        // "More than one record for topic found" -- confirmed live by hitting exactly that error
        // before this fix. A different topic sidesteps the collision entirely.
        String traceId = "0af7651916cd43dd8448eb211c80319c";
        String spanId = "b7ad6b7169203331";
        String payload = "{\"status\":\"FAILED\"}";
        outboxEventRepository.saveAndFlush(
                new OutboxEvent(UUID.randomUUID(), OutboxEventType.TRANSFER_FAILED, payload, traceId, spanId));

        outboxPublisher.publishPending();

        String topic = outboxPublisherProperties.topics().failed();
        var consumerProps = KafkaTestUtils.consumerProps(kafka.getBootstrapServers(), "outbox-publisher-it-trace", "true");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (Consumer<String, String> consumer = new KafkaConsumer<>(consumerProps)) {
            consumer.subscribe(List.of(topic));
            ConsumerRecord<String, String> record = KafkaTestUtils.getSingleRecord(consumer, topic, Duration.ofSeconds(10));

            var traceparentHeader = record.headers().lastHeader("traceparent");
            assertThat(traceparentHeader).isNotNull();
            String traceparent = new String(traceparentHeader.value(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(traceparent).contains(traceId);
        }
    }
}
