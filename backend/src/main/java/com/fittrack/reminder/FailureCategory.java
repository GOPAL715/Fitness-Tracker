package com.fittrack.reminder;

/**
 * Why one occurrence failed, in terms a user can act on.
 *
 * <p>Deliberately small and deliberately closed. Each constant names a cause the delivery path
 * actually distinguishes at the moment it decides - nothing here is inferred afterwards from a
 * message, a stack trace or a status code the pipeline has already collapsed. A cause the pipeline
 * cannot name is {@link #UNKNOWN}, which is an honest answer rather than a guess.
 *
 * <p>These are categories, not diagnostics. The detail behind one - which subscription, which HTTP
 * status, which exception type - stays in the server log, where it belongs.
 *
 * <p>Provider <em>configuration</em> is deliberately absent. "Push is not configured on this server"
 * is a deployment fact shown by {@code GET /api/v1/push/config}, not a property of a reminder, and
 * folding it in here would make a healthy scheduled reminder look broken.
 */
public enum FailureCategory {
    /** The user has no push subscription registered on any browser. */
    NO_SUBSCRIPTION,
    /** The push service said this subscription can never receive again (404 or 410). */
    INVALID_SUBSCRIPTION,
    /** The push service applied rate limiting (429). The subscription is valid; the send was throttled. */
    RATE_LIMITED,
    /** The push service or the network failed in a way that may succeed on a later attempt. */
    TEMPORARY_PROVIDER_ERROR,
    /** A delivery the channel positively rejected for a reason that will not change on retry. */
    PROVIDER_REJECTED,
    /**
     * The occurrence failed and the path had nothing more specific to say.
     *
     * <p>This is the common case, and it is the correct one for a deployment whose delivery
     * provider is not configured at all, or for any provider that reports failure without a
     * reason. It is never a placeholder standing in for a category that was available.
     */
    UNKNOWN
}
