package com.showcase.transfer.service;

import com.showcase.transfer.domain.OutboxEvent;
import com.showcase.transfer.domain.OutboxEventRepository;
import com.showcase.transfer.domain.OutboxEventType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.apache.kafka.common.errors.DisconnectException;
import org.apache.kafka.common.errors.NetworkException;
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
    private final Tracer tracer;

    public OutboxPublisher(OutboxEventRepository outboxEventRepository, KafkaTemplate<String, String> kafkaTemplate,
                            OutboxPublisherProperties properties, MeterRegistry meterRegistry, Tracer tracer) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.tracer = tracer;
        meterRegistry.gauge("transfer.outbox.backlog", outboxEventRepository,
                repository -> (double) repository.countByPublishedAtIsNull());
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(this::publishPending, properties.pollInterval().toMillis());
    }

    // Package-private so OutboxPublisherIT/OutboxPublisherTest can invoke it directly,
    // without going through the scheduler registration machinery -- same reasoning as
    // CompensationScheduler.
    void publishPending() {
        List<OutboxEvent> pending = outboxEventRepository.findByPublishedAtIsNullOrderByCreatedAtAsc(
                Limit.of(properties.batchSize()));
        for (int i = 0; i < pending.size(); i++) {
            OutboxEvent event = pending.get(i);
            try {
                publish(event);
            } catch (BrokerUnavailableException brokerDown) {
                // A broker-connectivity failure is not row-specific: every other row in this
                // tick's batch would fail the exact same way, each burning up to
                // publish-timeout/max.block.ms against a broker that is still down --
                // up to 500 rows * ~5-60s apiece for no benefit. Stop this tick here instead;
                // every row in `pending`, including this one, is still unpublished, so the
                // next poll-interval tick retries the whole backlog once the broker recovers.
                // Nothing is lost, only deferred. See publish()'s own comment for how this is
                // told apart from a row-specific failure.
                log.error("Outbox publish tick aborted: Kafka broker unreachable after {}, "
                                + "deferring the remaining {} row(s) in this batch to the next tick",
                        event.getId(), pending.size() - i, brokerDown.getCause());
                break;
            } catch (RuntimeException unexpected) {
                // One row's failure must not block the rest of this batch. The row is still
                // unpublished, so the next tick retries it -- same reasoning as
                // CompensationScheduler's loops.
                log.error("Outbox event {} failed to publish, will retry next tick", event.getId(), unexpected);
            }
        }
    }

    private void publish(OutboxEvent event) {
        if (event.getTraceId() == null || event.getSpanId() == null) {
            // No trace context captured at write time (e.g. a row from before this
            // column existed, or the outbox write happened with no active span) --
            // fall through to a normal send, which still gets its own fresh trace via
            // Spring Kafka's observation instrumentation, just not linked to anything.
            doPublish(event);
            return;
        }
        TraceContext parentContext = tracer.traceContextBuilder()
                .traceId(event.getTraceId())
                .spanId(event.getSpanId())
                .sampled(true)
                .build();
        Span span = tracer.spanBuilder().setParent(parentContext).name("outbox.publish").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            doPublish(event);
        } finally {
            span.end();
        }
    }

    private void doPublish(OutboxEvent event) {
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
        } catch (org.apache.kafka.common.errors.TimeoutException | java.util.concurrent.TimeoutException brokerTimeout) {
            // Two distinct timeouts, both meaning "this producer cannot currently talk to
            // Kafka", not "this row is bad":
            //  - org.apache.kafka.common.errors.TimeoutException is thrown SYNCHRONOUSLY by
            //    kafkaTemplate.send(...) itself, on the calling (scheduling) thread, before a
            //    Future is even returned to call .get() on -- this is the path taken when the
            //    producer cannot fetch topic metadata from the broker within max.block.ms
            //    (application.yml), i.e. the broker is unreachable.
            //  - java.util.concurrent.TimeoutException is our own .get(publishTimeout) bound
            //    expiring while a Future returned by send() is still pending -- e.g. the
            //    broker accepted the connection but never acked within delivery.timeout.ms.
            // Every other row in this batch would hit the same wall, so this is escalated to
            // BrokerUnavailableException rather than being treated as this row's problem.
            throw new BrokerUnavailableException(brokerTimeout);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof org.apache.kafka.common.errors.TimeoutException
                    || cause instanceof NetworkException
                    || cause instanceof DisconnectException) {
                // Same broker-unreachable family as above, just surfaced asynchronously
                // through the Future instead of thrown synchronously by send() -- e.g. the
                // in-flight request expired (delivery.timeout.ms) or the connection dropped
                // mid-send. Still not row-specific.
                throw new BrokerUnavailableException(cause);
            }
            // Anything else reaching here is specific to this row/record (e.g. a broker-side
            // rejection of this particular record) rather than broker connectivity -- log and
            // let the caller move on to the next row, same as before.
            log.error("Outbox event {} failed to publish to {}, will retry next tick", event.getId(), topic, failed);
        }
    }

    private String topicFor(OutboxEventType eventType) {
        return switch (eventType) {
            case TRANSFER_COMPLETED -> properties.topics().completed();
            case TRANSFER_FAILED -> properties.topics().failed();
        };
    }

    /**
     * Internal signal that publishPending()'s current batch should stop early because Kafka
     * itself -- not this one row -- is unreachable. Never escapes this class: publish() throws
     * it, publishPending() catches it and breaks the loop. Deliberately a RuntimeException
     * subtype caught BEFORE the existing generic {@code catch (RuntimeException unexpected)},
     * not a checked exception, so publish()'s signature doesn't have to change.
     */
    private static final class BrokerUnavailableException extends RuntimeException {
        BrokerUnavailableException(Throwable cause) {
            super(cause);
        }
    }
}
