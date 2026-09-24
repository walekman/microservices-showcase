package com.showcase.notification.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How a failed record is handled, in one place. Two families, decided by exception type:
 *
 * - Transient: the database is unreachable. Retried in place, forever, with capped exponential
 *   backoff. The partition waits (records behind it are not processed out of order) and nothing
 *   is lost or dead-lettered; it drains as soon as Postgres is back.
 * - Everything else: dead-lettered on the first failure to {@code <topic>-dlt}, and the
 *   partition moves on. That covers an undeserializable payload, an invalid or conflicting event
 *   (NotificationService), and any bug nobody anticipated.
 *
 * Retryability is an allowlist ({@link DefaultErrorHandler#defaultFalse()}), the reverse of
 * Spring Kafka's default. With an unlimited retry, the default -- retry anything not on a short
 * list of known-fatal types -- would let one unexpected exception stall a partition for good.
 * Here an unexpected exception costs one dead-lettered record that can be replayed after a fix.
 *
 * Blocking retry, not retry topics (@RetryableTopic), for the DB case on purpose: when the
 * database is down every record fails the same way, so moving each one to a retry topic would
 * only reorder events and fill the retry topics without letting anything else succeed.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationRetryProperties.class)
public class KafkaErrorHandlingConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaErrorHandlingConfig.class);

    // The recoverer publishes to the same partition number as the source record, so each DLT
    // needs at least as many partitions as its source topic -- one, as auto-created by the broker.
    @Bean
    NewTopic completedDeadLetterTopic(@Value("${notification.topics.completed}") String topic) {
        return TopicBuilder.name(topic + "-dlt").partitions(1).replicas(1).build();
    }

    @Bean
    NewTopic failedDeadLetterTopic(@Value("${notification.topics.failed}") String topic) {
        return TopicBuilder.name(topic + "-dlt").partitions(1).replicas(1).build();
    }

    // Boot hands a CommonErrorHandler bean to its auto-configured listener container factory.
    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaProperties kafkaProperties, ObjectMapper objectMapper,
                                         NotificationRetryProperties retry, MeterRegistry meterRegistry) {
        DeadLetterPublishingRecoverer recoverer =
                new DeadLetterPublishingRecoverer(deadLetterTemplate(kafkaProperties, objectMapper));

        ExponentialBackOff backOff = new ExponentialBackOff(retry.initialInterval().toMillis(), retry.multiplier());
        backOff.setMaxInterval(retry.maxInterval().toMillis());
        // No setMaxElapsedTime / setMaxAttempts: both default to unlimited.

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.defaultFalse();
        // Matched anywhere in the cause chain, so the listener's ListenerExecutionFailedException
        // wrapper does not hide them. These are the shapes "Postgres is unreachable" takes:
        // no connection for a new transaction, or a connection lost mid-statement.
        handler.addRetryableExceptions(
                CannotCreateTransactionException.class,
                DataAccessResourceFailureException.class,
                TransientDataAccessException.class,
                RecoverableDataAccessException.class);
        handler.setRetryListeners(new RetryListener() {
            @Override
            public void failedDelivery(ConsumerRecord<?, ?> record, Exception ex, int deliveryAttempt) {
                String cause = NestedExceptionUtils.getMostSpecificCause(ex).getClass().getSimpleName();
                meterRegistry.counter("notification.delivery.failures", "topic", record.topic(), "exception", cause)
                        .increment();
                log.warn("Delivery attempt {} of {}-{}@{} failed: {}", deliveryAttempt, record.topic(),
                        record.partition(), record.offset(), NestedExceptionUtils.getMostSpecificCause(ex).toString());
            }

            @Override
            public void recovered(ConsumerRecord<?, ?> record, Exception ex) {
                log.error("Dead-lettered {}-{}@{} to {}-dlt", record.topic(), record.partition(), record.offset(),
                        record.topic());
            }
        });
        return handler;
    }

    /**
     * Not a bean: a KafkaTemplate bean of our own would make Boot back off its auto-configured
     * one. The value is either the original bytes (a deserialization failure -- the recoverer
     * republishes exactly what could not be read) or the deserialized TransferEvent, so the
     * value serializer picks by type.
     */
    private static KafkaTemplate<Object, Object> deadLetterTemplate(KafkaProperties kafkaProperties,
                                                                    ObjectMapper objectMapper) {
        Map<Class<?>, Serializer<?>> byType = new LinkedHashMap<>();
        byType.put(byte[].class, new ByteArraySerializer());
        byType.put(Object.class, new JsonSerializer<>(objectMapper).noTypeInfo());
        @SuppressWarnings({"unchecked", "rawtypes"})
        Serializer<Object> keySerializer = (Serializer) new StringSerializer();
        DefaultKafkaProducerFactory<Object, Object> producerFactory = new DefaultKafkaProducerFactory<>(
                kafkaProperties.buildProducerProperties(null), keySerializer,
                new DelegatingByTypeSerializer(byType, true));
        return new KafkaTemplate<>(producerFactory);
    }
}
