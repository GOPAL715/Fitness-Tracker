package com.fittrack.health;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Technical validation of provider records.
 *
 * <h2>Scope: technical, not medical</h2>
 * This rejects records that are <em>structurally</em> impossible or unusable: a missing record id,
 * a null date, a negative count, a non-positive weight, a future date. It deliberately does
 * <b>not</b> judge whether a heart rate, HRV, body-fat or sleep value is medically plausible.
 * FitTrack has no clinical reference ranges, and inventing thresholds here would silently discard
 * a user's real data on the basis of a number nobody audited.
 *
 * <p>A record failing technical validation is dropped and counted, not fatal. One malformed row
 * must not fail the whole sync, and must never surface as an HTTP 500.
 *
 * <h2>Future dates</h2>
 * A provider may legitimately report the current day, so only strictly-future dates are rejected.
 * The comparison uses UTC, matching the rest of the analytics code, so the same data is accepted or
 * rejected regardless of where the process runs.
 */
public final class HealthRecordValidator {

    /** Reasons describe what is technically wrong with a record. They make no medical claim. */
    public static final String MISSING_RECORD_ID = "missing_record_id";
    public static final String INVALID_DATE = "invalid_date";
    public static final String FUTURE_DATE = "future_date";
    public static final String NEGATIVE_VALUE = "negative_value";
    public static final String INVALID_WEIGHT = "invalid_weight";
    public static final String EXCESSIVE_VALUE = "excessive_value";

    /**
     * Generous structural ceilings.
     *
     * <p>These bound what a counter column can physically hold, not what is healthy: a step count
     * is an {@code int}, and a value that cannot round-trip is corrupt regardless of health.
     */
    private static final long MAX_STEPS = 200_000L;
    private static final long MAX_CALORIES = 100_000L;
    private static final long MAX_ACTIVE_MINUTES = 1440L;
    private static final int MAX_CURSOR_LENGTH = 160;

    private HealthRecordValidator() {
    }

    /** Outcome of validating one sync: what survived, and why anything was dropped. */
    public record Result(List<HealthProvider.ActivityRecord> activity,
                         List<HealthProvider.BodyRecord> body,
                         int rejected,
                         List<String> reasons) {
    }

    /** Filters a batch down to records FitTrack can store, reporting what was dropped. */
    public static Result validate(List<HealthProvider.ActivityRecord> activity,
                                  List<HealthProvider.BodyRecord> body) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<String> reasons = new ArrayList<>();
        List<HealthProvider.ActivityRecord> goodActivity = new ArrayList<>();
        List<HealthProvider.BodyRecord> goodBody = new ArrayList<>();
        int rejected = 0;

        for (HealthProvider.ActivityRecord r : activity) {
            String reason = activityProblem(r, today);
            if (reason == null) {
                goodActivity.add(r);
            } else {
                rejected++;
                reasons.add(reason);
            }
        }
        for (HealthProvider.BodyRecord r : body) {
            String reason = bodyProblem(r, today);
            if (reason == null) {
                goodBody.add(r);
            } else {
                rejected++;
                reasons.add(reason);
            }
        }
        return new Result(goodActivity, goodBody, rejected, List.copyOf(reasons));
    }

    private static String activityProblem(HealthProvider.ActivityRecord r, LocalDate today) {
        if (r == null || r.recordId() == null || r.recordId().isBlank()) return MISSING_RECORD_ID;
        if (r.date() == null) return INVALID_DATE;
        if (r.date().isAfter(today)) return FUTURE_DATE;
        if (r.steps() < 0 || r.activeMinutes() < 0 || r.caloriesBurned() < 0) return NEGATIVE_VALUE;
        if (r.steps() > MAX_STEPS || r.caloriesBurned() > MAX_CALORIES) return EXCESSIVE_VALUE;
        // More than a full day of active minutes is structurally impossible, not a health claim.
        if (r.activeMinutes() > MAX_ACTIVE_MINUTES) return EXCESSIVE_VALUE;
        return null;
    }

    private static String bodyProblem(HealthProvider.BodyRecord r, LocalDate today) {
        if (r == null || r.recordId() == null || r.recordId().isBlank()) return MISSING_RECORD_ID;
        if (r.date() == null) return INVALID_DATE;
        if (r.date().isAfter(today)) return FUTURE_DATE;
        // Weight must be strictly positive: 0 or negative is corrupt, and the column check agrees.
        if (r.weightLb() != null && r.weightLb().signum() <= 0) return INVALID_WEIGHT;
        if (r.bodyFatPct() != null && r.bodyFatPct().signum() < 0) return NEGATIVE_VALUE;
        // A body-fat percentage cannot exceed 100 without being corrupt. That is a percentage
        // bound, not a body-composition opinion about any individual.
        if (r.bodyFatPct() != null && r.bodyFatPct().compareTo(new java.math.BigDecimal("100")) > 0) {
            return EXCESSIVE_VALUE;
        }
        return null;
    }

    /** A provider record id that fits the column, or null when it cannot be stored. */
    public static String usableRecordId(String recordId) {
        if (recordId == null || recordId.isBlank() || recordId.length() > 120) return null;
        return recordId;
    }

    /** A cursor that fits the column, or null when it must be treated as absent. */
    public static String usableCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        return cursor.length() > MAX_CURSOR_LENGTH ? null : cursor;
    }

    /** Guards against a provider returning a device row that is not this user's. */
    public static boolean isSameOwner(UUID expected, UUID actual) {
        return expected != null && expected.equals(actual);
    }
}
