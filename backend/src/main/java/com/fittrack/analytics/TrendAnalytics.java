package com.fittrack.analytics;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Daily, weekly and monthly rollups of existing rows (Phase 21).
 *
 * <h2>No materialized aggregate exists, and none is needed</h2>
 * Every series here is derived at read time from {@code v_daily_metrics_canonical},
 * {@code v_body_metrics_canonical} and {@code meals}. Those tables already carry an index on
 * {@code (user_id, date)}, so a bounded range scan is an index range read followed by an aggregate.
 * A daily row in this application is written a handful of times per day by a single user; the row
 * counts that would justify a rollup table do not arise, and a rollup would have to be invalidated
 * on every write to three source tables to stay correct.
 *
 * <h2>Bucketing</h2>
 * Buckets are computed in the database with {@code date_trunc}, over columns that are already
 * {@code date} rather than {@code timestamptz}. That choice is what makes the result
 * timezone-proof: a stored day is a calendar day the user asserted, so there is no instant to
 * convert and no DST transition that could move a value into a neighbouring bucket.
 *
 * <ul>
 *   <li><b>day</b> - the date itself.</li>
 *   <li><b>week</b> - ISO-8601, starting Monday. This matches {@code utils.dayIndex}, which already
 *       numbers weeks Monday-first for the client, so a week means the same thing on both sides.</li>
 *   <li><b>month</b> - the first of the calendar month.</li>
 * </ul>
 *
 * <p>A week or month that only partly overlaps the requested range is still reported, labelled with
 * the bucket's own start, and its {@code days} field says how many of its days the range actually
 * covered. Truncating it silently would make a partial bucket look like a complete one.
 */
@Component
public class TrendAnalytics {

    private final NamedParameterJdbcTemplate jdbc;

    public TrendAnalytics(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Activity rollup per bucket.
     *
     * <p>Sums are nullable, and that is the point. A day with no row in the canonical view has no
     * step count, and reporting {@code 0} would claim the user took no steps rather than that
     * nothing was recorded.
     */
    public List<Bucket> activity(LocalDate from, LocalDate to, String bucket, String userId) {
        String sql = """
                SELECT date_trunc(:unit, metric_date)::date AS bucket_start,
                       sum(steps)::bigint           AS steps,
                       sum(active_minutes)::bigint  AS active_minutes,
                       sum(calories_burned)::bigint AS calories_burned,
                       avg(sleep_hours)             AS sleep_hours_avg
                  FROM v_daily_metrics_canonical
                 WHERE user_id=CAST(:u AS uuid) AND metric_date BETWEEN :f AND :t
                 GROUP BY 1
                 ORDER BY 1
                """;
        // The explicit RowMapper generic: with a bare lambda the compiler cannot choose between the
        // RowMapper and ResultSetExtractor overloads, and the call does not compile.
        return jdbc.query(sql, new MapSqlParameterSource("u", userId)
                        .addValue("f", from).addValue("t", to).addValue("unit", truncUnit(bucket)),
                (org.springframework.jdbc.core.RowMapper<Bucket>) (rs, n) -> new Bucket(
                        rs.getObject("bucket_start", LocalDate.class),
                        (Long) rs.getObject("steps"),
                        (Long) rs.getObject("active_minutes"),
                        (Long) rs.getObject("calories_burned"),
                        rs.getBigDecimal("sleep_hours_avg")));
    }

    /** The first day of the bucket containing {@code date}, matching {@code date_trunc} exactly. */
    public static LocalDate bucketStart(LocalDate date, String bucket) {
        return switch (bucket) {
            // date_trunc('week', ...) is ISO-8601: it snaps back to Monday.
            case "week" -> date.minusDays(date.getDayOfWeek().getValue() - 1L);
            case "month" -> date.withDayOfMonth(1);
            default -> date;
        };
    }


    /**
     * Nutrition rollup per bucket.
     *
     * <p>Fiber is aggregated on exactly the same footing as the other macros: summed from
     * {@code meals.fiber_g} over the same rows in the same query. It is deliberately not derived
     * from {@code meal_items}, because {@code meals} is the level every other macro is read from and
     * mixing levels would let fiber disagree with calories on the same day.
     */
    public List<NutritionBucket> nutrition(LocalDate from, LocalDate to, String bucket, String userId) {
        String sql = """
                SELECT date_trunc(:unit, meal_date)::date AS bucket_start,
                       sum(calories)   AS calories,
                       sum(protein_g)  AS protein_g,
                       sum(carbs_g)    AS carbs_g,
                       sum(fat_g)      AS fat_g,
                       sum(fiber_g)    AS fiber_g
                  FROM meals
                 WHERE user_id=CAST(:u AS uuid) AND meal_date BETWEEN :f AND :t
                 GROUP BY 1
                 ORDER BY 1
                """;
        return jdbc.query(sql, new MapSqlParameterSource("u", userId)
                        .addValue("f", from).addValue("t", to).addValue("unit", truncUnit(bucket)),
                (org.springframework.jdbc.core.RowMapper<NutritionBucket>) (rs, n) -> new NutritionBucket(
                        rs.getObject("bucket_start", LocalDate.class),
                        rs.getBigDecimal("calories"), rs.getBigDecimal("protein_g"),
                        rs.getBigDecimal("carbs_g"), rs.getBigDecimal("fat_g"),
                        rs.getBigDecimal("fiber_g")));
    }

    /**
     * The unit {@code date_trunc} understands for a bucket.
     *
     * <p>{@code date_trunc} takes the literal string, so the value must be validated before it is
     * bound. {@link AnalyticsTimezone#bucket} has already reduced it to one of three words, which is
     * why no other value can reach this method.
     */
    private static String truncUnit(String bucket) {
        return switch (bucket) {
            case "week" -> "week";
            case "month" -> "month";
            default -> "day";
        };
    }

    /**
     * How many days of this bucket fall inside the requested range.
     *
     * <p>Reported so a partial bucket is visible as partial rather than compared against a full
     * week's total as though the user had been inactive for the rest of it.
     */
    public static long daysInBucket(LocalDate bucketStart, String bucket, LocalDate from, LocalDate to) {
        LocalDate end = switch (bucket) {
            case "week" -> bucketStart.plusDays(6);
            case "month" -> bucketStart.withDayOfMonth(bucketStart.lengthOfMonth());
            default -> bucketStart;
        };
        LocalDate lo = bucketStart.isBefore(from) ? from : bucketStart;
        LocalDate hi = end.isAfter(to) ? to : end;
        return hi.isBefore(lo) ? 0 : ChronoUnit.DAYS.between(lo, hi) + 1;
    }

    /**
     * An activity rollup for one bucket.
     *
     * <p>Every measure is a {@code Long} that is null when the bucket held no recorded data. A null
     * and a zero are different facts and the API keeps them distinguishable.
     */
    public record Bucket(LocalDate start, Long steps, Long activeMinutes, Long caloriesBurned,
                         BigDecimal sleepHoursAvg) {}

    /** A nutrition rollup for one bucket; nullable for the same reason as {@link Bucket}. */
    public record NutritionBucket(LocalDate start, BigDecimal calories, BigDecimal proteinG,
                                  BigDecimal carbsG, BigDecimal fatG, BigDecimal fiberG) {}
}