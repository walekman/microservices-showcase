package com.showcase.transfer.service;

import com.showcase.transfer.domain.OutboxEvent;
import com.showcase.transfer.domain.OutboxEventRepository;
import com.showcase.transfer.domain.OutboxEventType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-level proof for the fix in OutboxPublisher.publishPending(): a broker-connectivity
 * failure on one row must abort the rest of that tick's batch instead of retrying every
 * remaining row against a broker that is still down. Complements OutboxPublisherIT, which
 * proves the happy path against a real Kafka container but cannot cheaply simulate a
 * completely unreachable broker without hanging for real wall-clock seconds per row.
 */
@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        OutboxPublisherProperties properties = new OutboxPublisherProperties(
                Duration.ofSeconds(5), 500, Duration.ofSeconds(5), null);
        publisher = new OutboxPublisher(outboxEventRepository, kafkaTemplate, properties,
                new SimpleMeterRegistry(), Tracer.NOOP);
    }

    private OutboxEvent event() {
        return new OutboxEvent(UUID.randomUUID(), OutboxEventType.TRANSFER_COMPLETED, "{}");
    }

    @Test
    void aBrokerUnreachableFailureOnTheFirstRowAbortsTheRestOfTheBatch() {
        OutboxEvent first = event();
        OutboxEvent second = event();
        OutboxEvent third = event();
        when(outboxEventRepository.findByPublishedAtIsNullOrderByCreatedAtAsc(any(Limit.class)))
                .thenReturn(List.of(first, second, third));

        // Simulates the ASYNC path: send() returns a Future that later completes
        // exceptionally with Kafka's own TimeoutException -- e.g. delivery.timeout.ms expired
        // while the record sat unacked. Surfaces as ExecutionException when .get() is called.
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new TimeoutException("broker unreachable"));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(failed);

        publisher.publishPending();

        // The batch must stop after the first row: send() is never attempted for the second
        // or third event, and nothing is (falsely) marked published.
        verify(kafkaTemplate, times(1)).send(anyString(), anyString(), anyString());
        verify(outboxEventRepository, never()).save(any(OutboxEvent.class));
    }

    @Test
    void aSynchronousBrokerTimeoutThrownDirectlyFromSendAlsoAbortsTheBatch() {
        // The metadata-wait inside the real KafkaProducer.send() throws
        // org.apache.kafka.common.errors.TimeoutException SYNCHRONOUSLY on the calling
        // (scheduling) thread -- before a Future is even returned -- when it cannot reach the
        // broker within max.block.ms. This is the path a fully-down broker actually takes, and
        // it is NOT caught by the .get()-only try/catch that existed before this fix.
        // Simulated here by having the mocked send() itself throw rather than returning a
        // failed Future.
        OutboxEvent first = event();
        OutboxEvent second = event();
        when(outboxEventRepository.findByPublishedAtIsNullOrderByCreatedAtAsc(any(Limit.class)))
                .thenReturn(List.of(first, second));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenThrow(new TimeoutException("Topic not present in metadata after 5000 ms."));

        publisher.publishPending();

        verify(kafkaTemplate, times(1)).send(anyString(), anyString(), anyString());
    }

    @Test
    void aRowSpecificFailureDoesNotAbortTheBatchAndTheNextRowIsStillAttempted() {
        OutboxEvent first = event();
        OutboxEvent second = event();
        when(outboxEventRepository.findByPublishedAtIsNullOrderByCreatedAtAsc(any(Limit.class)))
                .thenReturn(List.of(first, second));

        // A failure that is none of the broker-unreachable exception types (Kafka's own
        // TimeoutException/NetworkException/DisconnectException) must still be treated as this
        // row's problem alone, exactly as before this fix -- the batch continues to the next row.
        CompletableFuture<SendResult<String, String>> rowSpecific = new CompletableFuture<>();
        rowSpecific.completeExceptionally(new IllegalStateException("record rejected for this row only"));
        CompletableFuture<SendResult<String, String>> succeeds = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(rowSpecific)
                .thenReturn(succeeds);

        publisher.publishPending();

        verify(kafkaTemplate, times(2)).send(anyString(), anyString(), anyString());
        verify(outboxEventRepository, times(1)).save(any(OutboxEvent.class));
    }

    @Test
    void publishingAnEventWithTraceContextStartsASpanParentedToIt() {
        Tracer tracer = mock(Tracer.class);
        Span parentSpan = mock(Span.class);
        Span.Builder spanBuilder = mock(Span.Builder.class);
        Tracer.SpanInScope scope = mock(Tracer.SpanInScope.class);
        TraceContext.Builder contextBuilder = mock(TraceContext.Builder.class);
        TraceContext reconstructed = mock(TraceContext.class);

        publisher = new OutboxPublisher(outboxEventRepository, kafkaTemplate,
                new OutboxPublisherProperties(Duration.ofSeconds(5), 500, Duration.ofSeconds(5), null),
                new SimpleMeterRegistry(), tracer);

        OutboxEvent event = new OutboxEvent(UUID.randomUUID(), OutboxEventType.TRANSFER_COMPLETED, "{}",
                "0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331");
        when(outboxEventRepository.findByPublishedAtIsNullOrderByCreatedAtAsc(any(Limit.class)))
                .thenReturn(List.of(event));
        when(tracer.traceContextBuilder()).thenReturn(contextBuilder);
        when(contextBuilder.traceId("0af7651916cd43dd8448eb211c80319c")).thenReturn(contextBuilder);
        when(contextBuilder.spanId("b7ad6b7169203331")).thenReturn(contextBuilder);
        when(contextBuilder.sampled(true)).thenReturn(contextBuilder);
        when(contextBuilder.build()).thenReturn(reconstructed);
        when(tracer.spanBuilder()).thenReturn(spanBuilder);
        when(spanBuilder.setParent(reconstructed)).thenReturn(spanBuilder);
        when(spanBuilder.name("outbox.publish")).thenReturn(spanBuilder);
        when(spanBuilder.start()).thenReturn(parentSpan);
        when(tracer.withSpan(parentSpan)).thenReturn(scope);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishPending();

        verify(tracer).withSpan(parentSpan);
        verify(scope).close();
        verify(parentSpan).end();
        verify(kafkaTemplate).send(anyString(), anyString(), anyString());
    }
}
