package com.fittrack.health;

import java.time.LocalDate;
import java.util.List;

/**
 * Provider-neutral health data source.
 *
 * <p>FitTrack ships no real wearable integration, so this boundary exists to keep sync logic
 * provider-agnostic and to let tests drive deterministic scenarios. Implementations must throw
 * {@link HealthProviderException} with a stable category rather than leaking transport detail.
 */
public interface HealthProvider {

    /** Stable failure categories; the client never sees provider internals. */
    String CATEGORY_TIMEOUT = "timeout";
    String CATEGORY_UNAVAILABLE = "unavailable";
    String CATEGORY_AUTH = "auth";
    String CATEGORY_MALFORMED = "malformed";

    /** Provider key, for example "fake-wearable". Never a credential. */
    String key();

    /**
     * Fetches records in a bounded window.
     *
     * @param from inclusive start date
     * @param to inclusive end date; must not be unbounded
     * @param cursor opaque resumption token, or null for a full window fetch
     */
    Batch fetch(LocalDate from, LocalDate to, String cursor);

    /** Everything a provider returns for one window. */
    record Batch(List<ActivityRecord> activity, List<BodyRecord> body, String nextCursor) {
        public Batch {
            activity = activity == null ? List.of() : List.copyOf(activity);
            body = body == null ? List.of() : List.copyOf(body);
        }
    }

    /**
     * A daily activity record.
     *
     * @param recordId provider-assigned identifier used for idempotent upserts
     */
    record ActivityRecord(String recordId, LocalDate date, int steps, long activeMinutes,
                          long caloriesBurned) {}

    record BodyRecord(String recordId, LocalDate date, java.math.BigDecimal weightLb,
                      java.math.BigDecimal bodyFatPct) {}
}
