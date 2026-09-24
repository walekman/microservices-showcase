package com.showcase.notification.service;

import com.showcase.notification.domain.Notification;
import com.showcase.notification.domain.NotificationRepository;
import com.showcase.notification.event.TransferEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Stores each transfer outcome once. Transfer's outbox publishes at least once -- OutboxPublisher
 * sends and only then marks the row published, so a crash in between re-sends the same event on
 * the next tick -- which makes an identical redelivery a normal, successful case here, not an
 * error.
 *
 * The lookup-then-insert is not racy: the record key is the transferId, so every event for one
 * transfer lands on the same partition and is consumed by one thread at a time. The unique
 * constraint on transferId is a backstop, not the mechanism.
 */
@Service
public class NotificationService {

    private final NotificationRepository repository;

    public NotificationService(NotificationRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public NotificationOutcome record(TransferEvent event, String topic, int partition, long offset) {
        validate(event);
        Optional<Notification> existing = repository.findByTransferId(event.transferId());
        if (existing.isPresent()) {
            if (existing.get().getStatus() != event.status()) {
                throw new ConflictingEventException(event.transferId(), existing.get().getStatus(), event.status());
            }
            return NotificationOutcome.DUPLICATE;
        }
        repository.save(new Notification(event, topic, partition, offset));
        return NotificationOutcome.STORED;
    }

    private void validate(TransferEvent event) {
        if (event == null) {
            // A Kafka tombstone (null value): nothing Transfer ever publishes.
            throw new InvalidEventException("Record has no payload");
        }
        if (event.transferId() == null) {
            throw new InvalidEventException("Event has no transferId");
        }
        if (event.status() == null) {
            throw new InvalidEventException("Event for transfer " + event.transferId() + " has no status");
        }
    }
}
