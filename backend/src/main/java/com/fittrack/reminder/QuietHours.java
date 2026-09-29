package com.fittrack.reminder;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Quiet-hours window arithmetic, in the user's own zone.
 *
 * <p>Pure computation: no clock, no database, no configuration. Everything is derived from the
 * instant it is given, so a test pins the instant rather than inheriting the time of day the suite
 * happens to run at.
 *
 * <h2>Window semantics</h2>
 * The window is half-open, {@code [start, end)}. {@code 22:00 -> 07:00} therefore means 22:00:00
 * through 06:59:59, and 07:00:00 itself is already outside it - which is what makes a reminder
 * deferred to the window's end eligible immediately, rather than deferring again forever.
 *
 * <p>A window where {@code end} is at or before {@code start} is treated as spanning midnight and
 * rolls into the following local day. {@code start == end} is rejected at the API and by the V14
 * check constraint, so the only way to reach that branch here is a row written before the
 * constraint existed; it resolves to a full 24 hours, which is the conservative reading of a
 * window whose length is genuinely ambiguous.
 *
 * <h2>Daylight saving</h2>
 * Both ends are resolved with {@link ZonedDateTime#atZone(ZoneId)} against the local date, so the
 * JVM's own transition rules apply and a wall-clock window keeps its meaning across a DST change.
 * No UTC offset is ever added or subtracted by hand. A start that falls in a spring-forward gap is
 * moved to the first instant that actually exists locally, so the window still begins rather than
 * resolving to a non-existent time.
 */
public final class QuietHours {

    private QuietHours() {}

    /**
     * Whether {@code at} falls inside the window.
     *
     * <p>Both candidate windows for the local date are considered: the one that starts on this
     * local date, and the one that started the previous local date and spills over midnight. That
     * is what makes an overnight window correct at both ends - 23:00 and 02:00 resolve against the
     * same window without either being special-cased.
     */
    public static boolean isQuiet(Instant at, ZoneId zone, LocalTime start, LocalTime end) {
        return windowContaining(at, zone, start, end).isPresent();
    }

    /**
     * The instant the current window ends, when {@code at} is inside one.
     *
     * <p>Empty when {@code at} is not quiet, which is the caller's signal that the occurrence is
     * already eligible and must be delivered rather than deferred.
     */
    public static Optional<Instant> endOfWindow(Instant at, ZoneId zone, LocalTime start, LocalTime end) {
        return windowContaining(at, zone, start, end).map(window -> window.to().toInstant());
    }

    /**
     * The window containing {@code at}, as its resolved start and end.
     *
     * <p>The two candidate start dates are the local date itself and the day before. Only a window
     * that strictly contains the instant is returned, so this yields at most one value and cannot
     * return two overlapping windows for a single instant.
     */
    private static Optional<Window> windowContaining(Instant at, ZoneId zone, LocalTime start, LocalTime end) {
        LocalDate localDate = at.atZone(zone).toLocalDate();
        for (LocalDate candidate : List.of(localDate.minusDays(1), localDate)) {
            Window window = windowStartingOn(candidate, zone, start, end);
            if (window.contains(at)) return Optional.of(window);
        }
        return Optional.empty();
    }

    private static Window windowStartingOn(LocalDate date, ZoneId zone, LocalTime start, LocalTime end) {
        ZonedDateTime from = date.atTime(start).atZone(zone);
        ZonedDateTime to = date.atTime(end).atZone(zone);
        // end <= start means the window runs past midnight into the next local day.
        if (!to.isAfter(from)) to = to.plusDays(1);
        return new Window(from, to);
    }

    /** A resolved [from, to) interval. */
    private record Window(ZonedDateTime from, ZonedDateTime to) {
        boolean contains(Instant at) {
            return !at.isBefore(from.toInstant()) && at.isBefore(to.toInstant());
        }
    }
}
