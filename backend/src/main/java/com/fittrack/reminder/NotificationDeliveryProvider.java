package com.fittrack.reminder;

import java.time.Instant;

/**
 * Provider-neutral delivery boundary for reminder notifications.
 *
 * <p>Reminder logic depends only on this interface, so adding email or SMS later does not change
 * the scheduler. Implementations report a three-way outcome; anything else is a defect.
 */
public interface NotificationDeliveryProvider {

    /** Outcome of a single delivery attempt. */
    enum Outcome {
        /** The notification was accepted by the channel. */
        DELIVERED,
        /** Transient failure: bounded retries are appropriate. */
        TEMPORARY_FAILURE,
        /** Permanent failure: retrying cannot succeed. */
        PERMANENT_FAILURE
    }

    /**
     * Attempts one delivery.
     *
     * @param occurrence the scheduled occurrence being delivered, used for channel dedupe keys
     * @return the outcome; never null
     */
    Outcome deliver(DeliveryRequest request);

    /** Channel identifier, never a credential. */
    String channel();

    /** What to deliver. Carries no provider credentials. */
    record DeliveryRequest(java.util.UUID reminderId, java.util.UUID userId, String title,
                           String message, Instant occurrence) {}
}
