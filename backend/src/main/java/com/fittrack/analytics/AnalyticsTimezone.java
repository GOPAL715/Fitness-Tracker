package com.fittrack.analytics;

import com.fittrack.health.UserTimezone;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The timezone an analytics request is answered in (Phase 21).
 *
 * <h2>Which column is the source</h2>
 * {@code app_users.timezone} and nothing else. That column is the user's own IANA zone, added in
 * V11 precisely because Phase 1-10 stored no user calendar, and it is the only one of the three
 * timezone columns in the schema that answers "which day is it for this person".
 *
 * <p>The other two are deliberately NOT consulted:
 * <ul>
 *   <li>{@code user_notification_preferences.timezone} resolves a quiet-hours wall-clock window.
 *       V14 states it is intentionally separate, and a user who has never configured notifications
 *       has no value there at all. Reusing it would silently answer a fitness question with a
 *       delivery setting.</li>
 *   <li>{@code reminders.timezone} is per-reminder scheduling. A user may have several reminders in
 *       several zones; there is no single one to read.</li>
 * </ul>
 *
 * <h2>Why UTC is a fallback and not a rejection</h2>
 * V11 leaves {@code app_users.timezone} NULL for every existing user and never invents a default,
 * because {@code UTC} is a real zone many people genuinely live in and seeding it would be
 * indistinguishable from a fabricated answer. That honesty must not become an outage: a user with no
 * zone still gets analytics, dated in UTC, rather than a 400 that tells them nothing about their
 * data. What changes is only which day is "today" for them.
 *
 * <p>{@link Resolved#resolved()} makes the distinction explicit on the wire, so a client can tell
 * "this is my real zone" from "this is a UTC default because you have not set one" rather than
 * having to guess from a {@code +00:00} offset.
 *
 * <h2>Why this cannot misfile history</h2>
 * Every aggregated column is already a {@code date} ({@code metric_date}, {@code meal_date},
 * {@code workout_date}, {@code session_date}), not a {@code timestamptz}. A stored day is a calendar
 * day the user asserted, so re-zoning it would corrupt it rather than correct it. The zone therefore
 * affects exactly one thing: which day is "today".
 */
public final class AnalyticsTimezone {

    private AnalyticsTimezone() {
    }

    /**
     * The zone an analytics request is answered in, and whether it is the user's own.
     *
     * @param zoneId   a zone this JVM can resolve; never null
     * @param resolved true when it came from {@code app_users.timezone}, false when it is the UTC
     *                 fallback for a user who has not set one
     */
    public record Resolved(String zoneId, boolean resolved) {

        /** The zone as a {@link ZoneId}, safe to derive "today" from. */
        public ZoneId zone() {
            return ZoneId.of(zoneId);
        }

        /** The current calendar day in this zone. */
        public LocalDate today() {
            return LocalDate.now(zone());
        }
    }

    /**
     * Resolves the caller's analytics timezone.
     *
     * @param storedTimezone the raw {@code app_users.timezone} value, which may be null or, if the
     *                       row were ever written outside the health path, an unrecognised string
     */
    public static Resolved resolve(String storedTimezone) {
        // UserTimezone.canonical is the single existing validator for this column. Reusing it means
        // a zone that health ingest would reject is equally unusable here, instead of analytics
        // inventing its own second opinion about which strings are real zones.
        String canonical = UserTimezone.canonical(storedTimezone);
        if (canonical != null) {
            return new Resolved(canonical, true);
        }
        return new Resolved(UserTimezone.FALLBACK_UTC, false);
    }

    /**
     * Parses and validates a caller-supplied bucket name.
     *
     * @throws IllegalArgumentException for anything outside the supported set, so an unknown bucket
     *         is a structured 400 rather than a silently different aggregation
     */
    public static String bucket(String requested) {
        String value = requested == null ? "day" : requested.trim().toLowerCase(java.util.Locale.ROOT);
        if (!value.equals("day") && !value.equals("week") && !value.equals("month")) {
            throw new IllegalArgumentException("bucket must be one of day, week or month");
        }
        return value;
    }
}