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

    /**
     * The category of the most recent {@link #deliver} call on this thread.
     *
     * <p>Phase 17 added this so a failed occurrence can be explained to the user in terms they can act
     * on, without changing what the three outcomes mean. The outcome still decides everything -
     * whether the occurrence is retried, and when it is closed - and is untouched. This only names a
     * cause that the provider had already determined while producing that outcome.
     *
     * <p>The default is {@link FailureCategory#UNKNOWN}, which is the correct answer for a provider
     * that reports an outcome without a reason, and keeps every existing implementation - including
     * the placeholder provider and the test doubles - compiling and behaving exactly as before.
     *
     * <p>Implementations must scope this per call, not to the instance: a provider is a singleton
     * shared by every scheduled occurrence.
     *
     * @return the category of the last failure, or {@link FailureCategory#UNKNOWN}; never null
     */
    default FailureCategory lastFailureCategory() {
        return FailureCategory.UNKNOWN;
    }

    /** Channel identifier, never a credential. */
    String channel();

    /** What to deliver. Carries no provider credentials. */
    record DeliveryRequest(java.util.UUID reminderId, java.util.UUID userId, String title,
                           String message, Instant occurrence) {}
}
