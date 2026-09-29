package com.fittrack.health;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns Health Connect source records into per-local-day contributions (D3, D5).
 *
 * <p>Pure function of its inputs: no database, no clock, no zone lookup beyond the {@link ZoneId} it
 * is handed. That is what makes the DST and midnight cases testable rather than something that only
 * shows up in production.
 *
 * <h2>Interval records: additive, split by overlap</h2>
 * Steps and active calories are additive over a day - Health Connect aggregates them with
 * {@code COUNT_TOTAL} and {@code ENERGY_TOTAL}, so summing a day's records is the documented way to
 * read the day. A record whose interval crosses local midnight genuinely belongs to two days, so it
 * is split by <b>duration overlap</b>.
 *
 * <p><b>Honest limitation, stated rather than hidden:</b> proportional splitting assumes the value is
 * spread evenly across the interval, which is an approximation. It is deterministic and standard, but
 * a record that front-loads its steps contributes slightly more to the first day than the true figure.
 * The alternatives are strictly worse: dropping the record loses data, and assigning it wholly to the
 * start day overstates one day by the entire value. The remainder goes to the <b>last</b> day touched,
 * so the split always sums back to exactly the original value and rounding can neither manufacture nor
 * lose steps.
 *
 * <h2>Instantaneous records: selected, never summed</h2>
 * Weight and body fat are single measurements. Health Connect aggregates them to
 * {@code WEIGHT_AVG}/{@code WEIGHT_MAX}/{@code WEIGHT_MIN} and never to a total, so adding a morning
 * and an evening weighing would be meaningless. Each belongs to the local day containing its instant,
 * and the day's value is the latest by instant, with the record id as a deterministic tie-breaker so
 * two readings in the same second still resolve to a stable winner.
 *
 * <h2>Daylight saving</h2>
 * Day bounds run from {@code atStartOfDay(zone)} to the next day's start, so a local day is correctly
 * 23 or 25 hours on a transition day. Overlap is measured in real elapsed time via {@link Instant},
 * never by adding 24 hours, so a spring-forward day neither gains nor loses an hour of data.
 */
public final class HealthConnectAggregator {

    private HealthConnectAggregator() {
    }

    /**
     * Splits one interval record's value across every local day its interval touches.
     *
     * @return local day to contributed value, chronological, summing exactly to {@code value}. Empty
     *         when the interval is empty or invalid.
     */
    public static Map<LocalDate, BigDecimal> splitIntervalByLocalDay(Instant start, Instant end,
                                                                    BigDecimal value, ZoneId zone) {
        Map<LocalDate, BigDecimal> out = new TreeMap<>();
        if (start == null || end == null || value == null || zone == null || !end.isAfter(start)) {
            return out;
        }
        // A zero VALUE is deliberately not short-circuited. "0 steps on this day" is a real
        // measurement and must produce a day with the value zero, not an absent day. Only a
        // zero-LENGTH interval is unusable, and that is already excluded above by the end-after-start
        // check; conflating the two would turn a genuine reading of nothing into no reading at all.
        Instant cursor = start;
        BigDecimal remaining = value;
        Duration total = Duration.between(start, end);
        while (cursor.isBefore(end)) {
            LocalDate day = cursor.atZone(zone).toLocalDate();
            Instant dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant();
            Instant sliceEnd = dayEnd.isBefore(end) ? dayEnd : end;
            Duration overlap = Duration.between(cursor, sliceEnd);
            BigDecimal share = overlap.compareTo(total) >= 0
                    ? remaining
                    : value.multiply(BigDecimal.valueOf(overlap.toNanos()))
                            .divide(BigDecimal.valueOf(total.toNanos()), 0, RoundingMode.HALF_UP);
            // The final day absorbs the rounding remainder so shares always sum back to `value`.
            if (!sliceEnd.isBefore(end) || share.compareTo(remaining) > 0) {
                share = remaining;
            }
            // Every day the interval touches is recorded, including one whose share rounds to zero.
            // Omitting a zero share would make a genuine "0 steps" day indistinguishable from a day
            // with no measurement at all, and the two are different facts.
            out.merge(day, share, BigDecimal::add);
            remaining = remaining.subtract(share);
            Instant next = sliceEnd;
            if (!next.isAfter(cursor)) {
                break; // Defensive: a pathological zone transition must not spin forever.
            }
            cursor = next;
        }
        return out;
    }

    /** The local day an instant falls on, for an instantaneous measurement. */
    public static LocalDate localDayOf(Instant instant, ZoneId zone) {
        return instant == null || zone == null ? null : ZonedDateTime.ofInstant(instant, zone).toLocalDate();
    }

    /**
     * Half-open bounds of a local day as instants, honouring daylight saving.
     *
     * <p>The end is the <b>next</b> day's start rather than {@code start + 24h}, so a transition day
     * measures 23 or 25 hours exactly as the user's clock did.
     */
    public static Instant[] dayBounds(LocalDate day, ZoneId zone) {
        return new Instant[]{day.atStartOfDay(zone).toInstant(), day.plusDays(1).atStartOfDay(zone).toInstant()};
    }

    /** Rounds a summed step or calorie total to the integer the column stores. */
    public static int toWholeNumber(BigDecimal value) {
        return value == null ? 0 : value.setScale(0, RoundingMode.HALF_UP).intValueExact();
    }
}
