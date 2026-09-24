package com.showcase.notification;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Makes dead-lettered records visible: one ERROR log line and one counter increment each.
 * Records stay parked on the DLT topics; replaying them is a manual step (see
 * docs/phase-11-notification-persistence-error-handling.md).
 *
 * Reads values as raw bytes, overriding the application-wide JSON deserializer: a DLT holds
 * exactly the records that could not be read or processed, so this listener must never fail
 * to read one itself. Own consumer group, so it tracks its own offsets on the DLTs.
 */
@Component
public class DeadLetterListener {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterListener.class);

    private final MeterRegistry meterRegistry;

    public DeadLetterListener(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(
            topics = {"${notification.topics.completed}-dlt", "${notification.topics.failed}-dlt"},
            groupId = "${spring.kafka.consumer.group-id}-dlt",
            properties = "value.deserializer=org.apache.kafka.common.serialization.ByteArrayDeserializer")
    public void onDeadLetter(ConsumerRecord<String, byte[]> record) {
        Headers headers = record.headers();
        String reason = reason(headers);
        meterRegistry.counter("notification.dead.lettered", "topic", record.topic(), "reason", reason).increment();
        log.error("Dead-lettered record from {}-{}@{}: {} -- {}; payload: {}",
                string(headers, KafkaHeaders.DLT_ORIGINAL_TOPIC),
                integer(headers, KafkaHeaders.DLT_ORIGINAL_PARTITION),
                longValue(headers, KafkaHeaders.DLT_ORIGINAL_OFFSET),
                reason,
                string(headers, KafkaHeaders.DLT_EXCEPTION_MESSAGE),
                record.value() == null ? null : new String(record.value(), StandardCharsets.UTF_8));
    }

    /**
     * The exception that decided the record's fate, by simple name. A listener's exception
     * reaches the recoverer wrapped in ListenerExecutionFailedException, so in that case the
     * root cause header is the informative one; a deserialization failure arrives unwrapped.
     */
    private static String reason(Headers headers) {
        String fqcn = string(headers, KafkaHeaders.DLT_EXCEPTION_FQCN);
        if (ListenerExecutionFailedException.class.getName().equals(fqcn)) {
            String cause = string(headers, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN);
            fqcn = cause != null ? cause : fqcn;
        }
        return fqcn == null ? "unknown" : fqcn.substring(fqcn.lastIndexOf('.') + 1);
    }

    private static String string(Headers headers, String key) {
        Header header = headers.lastHeader(key);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static Integer integer(Headers headers, String key) {
        Header header = headers.lastHeader(key);
        return header == null ? null : ByteBuffer.wrap(header.value()).getInt();
    }

    private static Long longValue(Headers headers, String key) {
        Header header = headers.lastHeader(key);
        return header == null ? null : ByteBuffer.wrap(header.value()).getLong();
    }
}
