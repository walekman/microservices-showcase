package com.showcase.notification.service;

public enum NotificationOutcome {
    /** First delivery of this transfer's outcome: a row was inserted. */
    STORED,
    /** Redelivery of an outcome already stored with the same status: nothing written. */
    DUPLICATE
}
