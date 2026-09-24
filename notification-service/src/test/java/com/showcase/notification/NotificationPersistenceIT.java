package com.showcase.notification;

import com.showcase.notification.domain.Notification;
import com.showcase.notification.domain.NotificationRepository;
import com.showcase.notification.event.TransferStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
class NotificationPersistenceIT {

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
    void storesEveryFieldOfTheTransferOutcomeAndWhereItWasReadFrom() {
        UUID transferId = UUID.randomUUID();
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        // The exact shape Transfer's TransferSaveService publishes (Boot's ObjectMapper: ISO instants).
        kafkaTemplate.send("transfer.failed", transferId.toString(), """
                {"transferId":"%s","fromAccountId":"%s","toAccountId":"%s","amount":12.50,
                 "status":"COMPENSATED","failureCode":"DESTINATION_ACCOUNT_BLOCKED",
                 "failureReason":"blocked","settledAt":"2026-09-24T10:15:30.123Z"}
                """.formatted(transferId, from, to));

        Notification stored = await().atMost(Duration.ofSeconds(20))
                .until(() -> repository.findByTransferId(transferId).orElse(null), n -> n != null);

        assertThat(stored.getStatus()).isEqualTo(TransferStatus.COMPENSATED);
        assertThat(stored.getFromAccountId()).isEqualTo(from);
        assertThat(stored.getToAccountId()).isEqualTo(to);
        assertThat(stored.getAmount()).isEqualByComparingTo(new BigDecimal("12.50"));
        assertThat(stored.getFailureCode()).isEqualTo("DESTINATION_ACCOUNT_BLOCKED");
        assertThat(stored.getFailureReason()).isEqualTo("blocked");
        assertThat(stored.getSettledAt()).isEqualTo(Instant.parse("2026-09-24T10:15:30.123Z"));
        assertThat(stored.getKafkaTopic()).isEqualTo("transfer.failed");
        assertThat(stored.getReceivedAt()).isNotNull();
        // A pre-Phase-12 payload (no conversion fields) still stores cleanly, with them left empty.
        assertThat(stored.getSourceCurrency()).isNull();
        assertThat(stored.getCreditAmount()).isNull();
    }

    @Test
    void storesTheConversionTheTransferLocked() {
        UUID transferId = UUID.randomUUID();
        kafkaTemplate.send("transfer.completed", transferId.toString(), """
                {"transferId":"%s","fromAccountId":"%s","toAccountId":"%s","amount":40.00,
                 "sourceCurrency":"PLN","destinationCurrency":"EUR","rate":0.22819,"creditAmount":9.13,
                 "status":"COMPLETED","settledAt":"2026-09-24T10:15:30.123Z"}
                """.formatted(transferId, UUID.randomUUID(), UUID.randomUUID()));

        Notification stored = await().atMost(Duration.ofSeconds(20))
                .until(() -> repository.findByTransferId(transferId).orElse(null), n -> n != null);

        assertThat(stored.getSourceCurrency()).isEqualTo("PLN");
        assertThat(stored.getDestinationCurrency()).isEqualTo("EUR");
        assertThat(stored.getRate()).isEqualByComparingTo("0.22819");
        assertThat(stored.getCreditAmount()).isEqualByComparingTo("9.13");
    }

    @Test
    void anIdenticalRedeliveryIsAcknowledgedWithoutASecondRowOrADeadLetter() {
        UUID transferId = UUID.randomUUID();
        String payload = "{\"transferId\":\"%s\",\"status\":\"COMPLETED\",\"amount\":5.00}".formatted(transferId);
        double duplicatesBefore = count("notification.events", "outcome", "duplicate");
        double deadLetteredBefore = count("notification.dead.lettered", "topic", "transfer.completed-dlt");

        kafkaTemplate.send("transfer.completed", transferId.toString(), payload);
        kafkaTemplate.send("transfer.completed", transferId.toString(), payload);

        await().atMost(Duration.ofSeconds(20))
                .until(() -> count("notification.events", "outcome", "duplicate") > duplicatesBefore);
        assertThat(repository.findAll()).filteredOn(n -> n.getTransferId().equals(transferId)).hasSize(1);
        assertThat(count("notification.dead.lettered", "topic", "transfer.completed-dlt"))
                .isEqualTo(deadLetteredBefore);
    }

    private double count(String name, String tag, String value) {
        return meterRegistry.find(name).tag(tag, value).counters().stream().mapToDouble(Counter::count).sum();
    }
}
