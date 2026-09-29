package com.fittrack.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The aggregation algorithm, tested as a pure function (D3, D6).
 *
 * <p>Health Connect cannot be exercised from this repository - it is an on-device Android API and the
 * bridge is a separate project. What <em>can</em> be tested exhaustively is the arithmetic that turns
 * its records into calendar days: the midnight boundary, the timezone dependency, and the daylight
 * saving transitions. Those are exactly the cases that silently corrupt health data in production and
 * are invisible in a happy-path integration test, so they are pinned here.
 */
class HealthConnectAggregatorTest {

    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private static Instant at(ZoneId zone, LocalDateTime local) {
        return ZonedDateTime.of(local, zone).toInstant();
    }

    private static BigDecimal n(String value) {
        return new BigDecimal(value);
    }

    @Nested
    @DisplayName("a record inside one local day")
    class SingleDay {

        @Test
        @DisplayName("a record that fits inside a day contributes all of its value to that day")
        void wholeValueLandsOnItsDay() {
            Map<LocalDate, BigDecimal> split = HealthConnectAggregator.splitIntervalByLocalDay(
                    at(KOLKATA, LocalDateTime.of(2026, 3, 10, 8, 0)),
                    at(KOLKATA, LocalDateTime.of(2026, 3, 10, 9, 0)),
                    n("500"), KOLKATA);

            assertThat(split).containsExactly(Map.entry(LocalDate.of(2026, 3, 10), n("500")));
        }

        @Test
        @DisplayName("a zero reading is a real measurement and is not turned into a missing one")
        void zeroIsHonoured() {
            // Health Connect does report zero steps for a genuinely inactive interval. A day that
            // measured zero is a day that was measured, so the split must yield that day mapped to
            // zero. Returning an empty map would make "0 steps" indistinguishable from "no record",
            // and the canonical views deliberately read NULL as absent.
            LocalDate day = LocalDate.of(2026, 3, 10);
            Map<LocalDate, BigDecimal> split = HealthConnectAggregator.splitIntervalByLocalDay(
                    at(KOLKATA, LocalDateTime.of(2026, 3, 10, 8, 0)),
                    at(KOLKATA, LocalDateTime.of(2026, 3, 10, 9, 0)),
                    BigDecimal.ZERO, KOLKATA);

            assertThat(split)
                    .as("a zero reading must produce the day, not nothing")
                    .containsExactly(Map.entry(day, BigDecimal.ZERO));
            assertThat(HealthConnectAggregator.toWholeNumber(BigDecimal.ZERO)).isZero();
        }
    }

    @Nested
    @DisplayName("a record crossing a DST transition")
    class DaylightSaving {
        @Test
        @DisplayName("an interval crossing a DST midnight still conserves its total")
        void conservesValueAcrossDstMidnight() {
            Map<LocalDate, BigDecimal> split = HealthConnectAggregator.splitIntervalByLocalDay(
                    at(LONDON, LocalDateTime.of(2026, 3, 28, 22, 0)),
                    at(LONDON, LocalDateTime.of(2026, 3, 29, 2, 0)),
                    n("600"), LONDON);

            assertThat(split.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                    .as("a DST-affected crossing must still sum back to the original value")
                    .isEqualByComparingTo("600");
        }
    }

    @Nested
    @DisplayName("a local day spans the clock, not a fixed 24 hours")
    class DayLength {

        @Test
        @DisplayName("a spring-forward day is 23 hours of real time")
        void springForwardDayIsShort() {
            LocalDate day = LocalDate.of(2026, 3, 29); // Europe/London moves 01:00 -> 02:00
            Instant[] bounds = HealthConnectAggregator.dayBounds(day, LONDON);

            long hours = Duration.between(bounds[0], bounds[1]).toHours();
            assertThat(hours).as("a spring-forward local day is 23 hours").isEqualTo(23L);
        }

        @Test
        @DisplayName("an autumn-back day is 25 hours of real time")
        void autumnBackDayIsLong() {
            LocalDate day = LocalDate.of(2026, 10, 25); // Europe/London moves 02:00 -> 01:00
            Instant[] bounds = HealthConnectAggregator.dayBounds(day, LONDON);

            long hours = Duration.between(bounds[0], bounds[1]).toHours();
            assertThat(hours).as("a fall-back local day is 25 hours").isEqualTo(25L);
        }

        @Test
        @DisplayName("an ordinary day is exactly 24 hours")
        void ordinaryDayIs24Hours() {
            Instant[] bounds = HealthConnectAggregator.dayBounds(LocalDate.of(2026, 6, 15), LONDON);

            assertThat(Duration.between(bounds[0], bounds[1]).toHours()).isEqualTo(24L);
        }

        @Test
        @DisplayName("the day bound is the next day's start, never start plus 24 hours")
        void boundsAreContiguous() {
            LocalDate day = LocalDate.of(2026, 3, 29);
            Instant[] first = HealthConnectAggregator.dayBounds(day, LONDON);
            Instant[] second = HealthConnectAggregator.dayBounds(day.plusDays(1), LONDON);

            assertThat(first[1])
                    .as("consecutive days must meet exactly, with no gap and no overlap on a DST day")
                    .isEqualTo(second[0]);
        }
    }
    @Nested
    @DisplayName("a record crossing local midnight")
    class MidnightCrossing {

        @Test
        @DisplayName("an interval spanning midnight is split across both days and conserves its total")
        void splitsAcrossBothDays() {
            Map<LocalDate, BigDecimal> split = HealthConnectAggregator.splitIntervalByLocalDay(
                    at(KOLKATA, LocalDateTime.of(2026, 1, 1, 22, 0)),
                    at(KOLKATA, LocalDateTime.of(2026, 1, 2, 2, 0)),
                    n("600"), KOLKATA);

            assertThat(split).containsOnlyKeys(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2));
            assertThat(split.get(LocalDate.of(2026, 1, 1)))
                    .as("2 of 4 hours fall on the first day")
                    .isEqualByComparingTo("300");
            assertThat(split.get(LocalDate.of(2026, 1, 2)))
                    .as("the other 2 of 4 hours fall on the second day")
                    .isEqualByComparingTo("300");
            assertThat(split.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                    .as("splitting may never invent or lose steps")
                    .isEqualByComparingTo("600");
        }

        @Test
        @DisplayName("an interval ending exactly at midnight lands wholly on the earlier day")
        void exactMidnightBoundaryStaysPut() {
            Map<LocalDate, BigDecimal> split = HealthConnectAggregator.splitIntervalByLocalDay(
                    at(KOLKATA, LocalDateTime.of(2026, 1, 1, 20, 0)),
                    at(KOLKATA, LocalDateTime.of(2026, 1, 2, 0, 0)),
                    n("400"), KOLKATA);

            assertThat(split)
                    .as("a half-open interval [start, end) must not spill into the next day")
                    .containsExactly(Map.entry(LocalDate.of(2026, 1, 1), n("400")));
        }

        @Test
        @DisplayName("a record starting exactly at midnight belongs to the new day")
        void startAtMidnightIsTheNewDay() {
            Map<LocalDate, BigDecimal> split = HealthConnectAggregator.splitIntervalByLocalDay(
                    at(KOLKATA, LocalDate.of(2026, 1, 2).atStartOfDay()),
                    at(KOLKATA, LocalDateTime.of(2026, 1, 2, 6, 0)),
                    n("500"), KOLKATA);

            assertThat(split).containsExactly(Map.entry(LocalDate.of(2026, 1, 2), n("500")));
        }

        @Test
        @DisplayName("a multi-day record spreads evenly and still sums back to its value")
        void multiDaySplitConservesTotal() {
            Map<LocalDate, BigDecimal> split = HealthConnectAggregator.splitIntervalByLocalDay(
                    at(KOLKATA, LocalDateTime.of(2026, 1, 1, 0, 0)),
                    at(KOLKATA, LocalDateTime.of(2026, 1, 4, 0, 0)),
                    n("900"), KOLKATA);

            assertThat(split).hasSize(3);
            assertThat(split.keySet()).containsExactly(
                    LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 3));
            assertThat(split.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                    .as("a three-day record must still total its original value")
                    .isEqualByComparingTo("900");
        }
    }

    @Nested
    @DisplayName("the user's zone decides the calendar day, not the server's")
    class TimezoneMatters {

        @Test
        @DisplayName("the same instant is a different local day in different zones")
        void zoneChangesTheDay() {
            // 18:30 UTC is already the next calendar day in Kolkata (+05:30) and still the same day
            // in New York (-04:00). Filing this by the server's zone would be wrong for both.
            Instant instant = Instant.parse("2026-05-05T18:30:00Z");

            assertThat(HealthConnectAggregator.localDayOf(instant, KOLKATA))
                    .isEqualTo(LocalDate.of(2026, 5, 6));
            assertThat(HealthConnectAggregator.localDayOf(instant, NEW_YORK))
                    .isEqualTo(LocalDate.of(2026, 5, 5));
        }

        @Test
        @DisplayName("the same record splits differently depending on the zone")
        void splitDependsOnZone() {
            Instant start = Instant.parse("2026-05-05T18:00:00Z");
            Instant end = Instant.parse("2026-05-05T20:00:00Z");
            // 18:00-20:00 UTC is 23:30-01:30 in Kolkata (+05:30), so it genuinely straddles local
            // midnight there and MUST split. In New York (-04:00) it is 14:00-16:00 on the 5th and
            // must not split at all. The same two instants therefore file onto different days, which
            // is exactly the misfiling a server-local or UTC calendar would introduce.
            Map<LocalDate, BigDecimal> kolkata =
                    HealthConnectAggregator.splitIntervalByLocalDay(start, end, n("600"), KOLKATA);
            Map<LocalDate, BigDecimal> newYork =
                    HealthConnectAggregator.splitIntervalByLocalDay(start, end, n("600"), NEW_YORK);

            assertThat(kolkata.keySet())
                    .as("23:30-01:30 local crosses midnight, so the record belongs to two days")
                    .containsExactlyInAnyOrder(LocalDate.of(2026, 5, 5), LocalDate.of(2026, 5, 6));
            assertThat(newYork.keySet())
                    .as("14:00-16:00 local sits well inside one day")
                    .containsExactly(LocalDate.of(2026, 5, 5));
            assertThat(kolkata.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                    .as("the split must conserve the value in either zone")
                    .isEqualByComparingTo("600");
        }
    }

    @Nested
    @DisplayName("degenerate input")
    class Degenerate {

        @ParameterizedTest
        @ValueSource(strings = { "zero-length", "reversed", "null-value" })
        @DisplayName("an interval that cannot be attributed produces nothing rather than a guess")
        void unrepresentableIntervalsYieldNothing(String kind) {
            Instant start = at(KOLKATA, LocalDateTime.of(2026, 3, 10, 8, 0));
            Instant end = at(KOLKATA, LocalDateTime.of(2026, 3, 10, 9, 0));
            BigDecimal value = n("100");
            if ("zero-length".equals(kind)) {
                end = start;
            } else if ("reversed".equals(kind)) {
                end = start.minusSeconds(3600);
            } else {
                value = null;
            }

            assertThat(HealthConnectAggregator.splitIntervalByLocalDay(start, end, value, KOLKATA))
                    .as("%s must not be turned into a fabricated contribution", kind)
                    .isEmpty();
        }

        @Test
        @DisplayName("a missing instant or zone yields no day rather than a default one")
        void missingInputsYieldNoDay() {
            Instant instant = at(KOLKATA, LocalDateTime.of(2026, 3, 10, 8, 0));

            assertThat(HealthConnectAggregator.localDayOf(null, KOLKATA)).isNull();
            assertThat(HealthConnectAggregator.localDayOf(instant, null)).isNull();
        }
    }

    @Nested
    @DisplayName("whole-number conversion for the integer columns")
    class WholeNumbers {

        @Test
        @DisplayName("a fractional calorie total rounds half up to the stored integer")
        void roundsHalfUp() {
            assertThat(HealthConnectAggregator.toWholeNumber(n("123.4"))).isEqualTo(123);
            assertThat(HealthConnectAggregator.toWholeNumber(n("123.5"))).isEqualTo(124);
            assertThat(HealthConnectAggregator.toWholeNumber(n("123.6"))).isEqualTo(124);
        }

        @Test
        @DisplayName("an absent total converts to zero rather than throwing")
        void nullIsZero() {
            assertThat(HealthConnectAggregator.toWholeNumber(null)).isZero();
        }
}}
