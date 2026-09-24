// transfer-service/src/test/java/com/showcase/transfer/service/TransferSaveServiceTest.java
package com.showcase.transfer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.showcase.transfer.domain.OutboxEvent;
import com.showcase.transfer.domain.OutboxEventRepository;
import com.showcase.transfer.domain.Transfer;
import com.showcase.transfer.domain.TransferFailureCode;
import com.showcase.transfer.domain.TransferRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferSaveServiceTest {

    @Mock
    private TransferRepository transferRepository;
    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private Tracer tracer;
    @Mock
    private Span span;
    @Mock
    private TraceContext traceContext;

    private SimpleMeterRegistry meterRegistry;
    private TransferSaveService service;

    @BeforeEach
    void setUp() {
        // A plain ObjectMapper does not know how to serialize java.time.Instant without this
        // module -- Spring's auto-configured bean has it registered already, but a
        // hand-built one in a unit test needs it explicitly, or toPayload() throws.
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        meterRegistry = new SimpleMeterRegistry();
        service = new TransferSaveService(transferRepository, outboxEventRepository, objectMapper, meterRegistry, tracer);
    }

    @Test
    void writesAnOutboxRowForACompletedTransfer() {
        Transfer transfer = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), UUID.randomUUID());
        transfer.markCompleted();
        when(transferRepository.save(transfer)).thenReturn(transfer);

        service.save(transfer);

        verify(outboxEventRepository).save(any());
    }

    @Test
    void writesNoOutboxRowForATransientStatus() {
        Transfer transfer = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), UUID.randomUUID());
        transfer.markCompensationRequired(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, "credit leg timed out");
        when(transferRepository.save(transfer)).thenReturn(transfer);

        service.save(transfer);

        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    void writesAnOutboxRowForEachTerminalStatus() {
        Transfer completed = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), UUID.randomUUID());
        completed.markCompleted();
        Transfer failed = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), UUID.randomUUID());
        failed.markFailed(TransferFailureCode.INSUFFICIENT_FUNDS, "not enough money");
        when(transferRepository.save(completed)).thenReturn(completed);
        when(transferRepository.save(failed)).thenReturn(failed);

        service.save(completed);
        service.save(failed);

        verify(outboxEventRepository, org.mockito.Mockito.times(2)).save(any());
    }

    @Test
    void incrementsCompletedCounterForACompletedTransfer() {
        Transfer transfer = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), UUID.randomUUID());
        transfer.markCompleted();
        when(transferRepository.save(transfer)).thenReturn(transfer);

        service.save(transfer);

        assertThat(meterRegistry.counter("transfers.completed").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("transfers.failed").count()).isEqualTo(0.0);
    }

    @Test
    void incrementsFailedCounterForAFailedTransfer() {
        Transfer transfer = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), UUID.randomUUID());
        transfer.markFailed(TransferFailureCode.INSUFFICIENT_FUNDS, "not enough money");
        when(transferRepository.save(transfer)).thenReturn(transfer);

        service.save(transfer);

        assertThat(meterRegistry.counter("transfers.failed").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("transfers.fraud_rejected").count()).isEqualTo(0.0);
    }

    @Test
    void incrementsFraudRejectedCounterAlongsideFailedForABlockedSourceAccount() {
        Transfer transfer = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), UUID.randomUUID());
        transfer.markFailed(TransferFailureCode.SOURCE_ACCOUNT_BLOCKED, "source blocked");
        when(transferRepository.save(transfer)).thenReturn(transfer);

        service.save(transfer);

        assertThat(meterRegistry.counter("transfers.failed").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("transfers.fraud_rejected").count()).isEqualTo(1.0);
    }

    @Test
    void incrementsFraudRejectedCounterForABlockedDestinationAccount() {
        Transfer transfer = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), UUID.randomUUID());
        transfer.markFailed(TransferFailureCode.DESTINATION_ACCOUNT_BLOCKED, "destination blocked");
        when(transferRepository.save(transfer)).thenReturn(transfer);

        service.save(transfer);

        assertThat(meterRegistry.counter("transfers.fraud_rejected").count()).isEqualTo(1.0);
    }

    @Test
    void incrementsNoCounterForATransientStatus() {
        Transfer transfer = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), UUID.randomUUID());
        transfer.markCompensationRequired(TransferFailureCode.ACCOUNT_SERVICE_UNAVAILABLE, "credit leg timed out");
        when(transferRepository.save(transfer)).thenReturn(transfer);

        service.save(transfer);

        assertThat(meterRegistry.counter("transfers.completed").count()).isEqualTo(0.0);
        assertThat(meterRegistry.counter("transfers.failed").count()).isEqualTo(0.0);
    }

    @Test
    void capturesTraceContextOnTheOutboxRowWhenASpanIsActive() {
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(traceContext);
        when(traceContext.traceId()).thenReturn("0af7651916cd43dd8448eb211c80319c");
        when(traceContext.spanId()).thenReturn("b7ad6b7169203331");
        Transfer transfer = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), UUID.randomUUID());
        transfer.markCompleted();
        when(transferRepository.save(transfer)).thenReturn(transfer);

        service.save(transfer);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        assertThat(captor.getValue().getTraceId()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
        assertThat(captor.getValue().getSpanId()).isEqualTo("b7ad6b7169203331");
    }

    @Test
    void leavesTraceContextNullWhenNoSpanIsActive() {
        when(tracer.currentSpan()).thenReturn(null);
        Transfer transfer = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), UUID.randomUUID());
        transfer.markCompleted();
        when(transferRepository.save(transfer)).thenReturn(transfer);

        service.save(transfer);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        assertThat(captor.getValue().getTraceId()).isNull();
        assertThat(captor.getValue().getSpanId()).isNull();
    }

    @Test
    void theOutboxPayloadCarriesTheLockedConversion() throws Exception {
        Transfer transfer = new Transfer(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("40.00"), UUID.randomUUID());
        transfer.lockConversion("PLN", "EUR", new BigDecimal("0.22819"), LocalDate.of(2026, 9, 23));
        transfer.markCompleted();
        when(transferRepository.save(transfer)).thenReturn(transfer);

        service.save(transfer);

        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(event.capture());
        com.fasterxml.jackson.databind.JsonNode payload = new ObjectMapper().readTree(event.getValue().getPayload());
        assertThat(payload.get("sourceCurrency").asText()).isEqualTo("PLN");
        assertThat(payload.get("destinationCurrency").asText()).isEqualTo("EUR");
        assertThat(payload.get("rate").decimalValue()).isEqualByComparingTo("0.22819");
        assertThat(payload.get("creditAmount").decimalValue()).isEqualByComparingTo("9.13");
    }
}
