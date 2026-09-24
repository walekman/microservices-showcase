package com.showcase.notification.service;

import com.showcase.notification.event.TransferStatus;

import java.util.UUID;

/**
 * A second event for an already-notified transfer that disagrees with the first. A transfer's
 * published status is terminal, so this means a producer defect, not a redelivery. Permanent:
 * dead-lettered on the first failure, and the stored row is left as it was.
 */
public class ConflictingEventException extends RuntimeException {

    public ConflictingEventException(UUID transferId, TransferStatus stored, TransferStatus received) {
        super("Transfer " + transferId + " already notified as " + stored + ", received " + received);
    }
}
