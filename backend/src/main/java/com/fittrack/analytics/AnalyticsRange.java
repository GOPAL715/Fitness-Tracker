package com.fittrack.analytics;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * The one inclusive date-range contract shared by every analytics and calendar read (Phase 21).
 *
 * <h2>Why this class exists</h2>
 * The bound used to be written twice and the two copies disagreed:
 * {@code AnalyticsController} rejected a span greater than 365 days, so at most 366 inclusive days
 * were accepted, while {@code CalendarController} compared {@code from.plusDays(366).isBefore(to)}
 * and therefore accepted 367 inclusive days. A user could be told "range must not exceed 366 days"
 * by one endpoint and then have the following day accepted by another for the same window.
 *
 * <h2>The contract</h2>
 * A range is valid when {@code from <= to} and it spans at most {@value #MAX_INCLUSIVE_DAYS}
 * <em>inclusive</em> days, so {@code from == to} is a legal one-day range and
 * {@code from.plusDays(365)} is the largest legal {@code to}. The day count is inclusive on both
 * ends, which is what a caller means by "2025-01-01 to 2026-01-01" and what the error message says.
 *
 * <p>Both controllers validate through {@link #validate} so the rule cannot drift again.
 */
public final class AnalyticsRange {

    /** The widest window one request may ask for, counted inclusively. */
    public static final int MAX_INCLUSIVE_DAYS = 366;

    private AnalyticsRange() {
    }

    /**
     * Rejects an inverted or over-long range.
     *
     * @throws IllegalArgumentException with a message that is part of the API contract, surfaced as
     *         a structured 400 by {@code GlobalExceptionHandler}
     */
    public static void validate(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from and to are required");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from must not be after to");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_INCLUSIVE_DAYS) {
            throw new IllegalArgumentException("range must not exceed " + MAX_INCLUSIVE_DAYS + " days");
        }
    }

    /**
     * The resolved window, applying an endpoint's default width when a bound is omitted.
     *
     * <p>A missing {@code to} means "up to and including today in the caller's resolved zone"; a
     * missing {@code from} means "the last {@code defaultDays} inclusive days ending there".
     *
     * @param today       the current day in the caller's resolved timezone
     * @param defaultDays the endpoint's default inclusive window width
     */
    public static LocalDate[] resolve(LocalDate from, LocalDate to, LocalDate today, int defaultDays) {
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.minusDays(defaultDays - 1L) : from;
        validate(start, end);
        return new LocalDate[]{start, end};
    }
}