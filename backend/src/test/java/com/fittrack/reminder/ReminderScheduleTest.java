package com.fittrack.reminder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The next-occurrence calculation, as a pure function of its inputs.
 *
 * <p>{@link ReminderSchedule#nextOccurrence} takes the reference instant as an argument rather than
 * reading a clock, so every case here is pinned to an explicit instant. That is what makes the
 * "before or after today's reminder" distinction - the thing an end-to-end test can only reach
 * through a fixed clock - directly assertable here.
 */
class ReminderScheduleTest {

    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
    private static final LocalTime SEVEN = LocalTime.of(7, 0);
    private static final String EVERY_DAY = "1,2,3,4,5,6,0";

    private static Instant daily(Instant from, ZoneId zone) {
        return ReminderSchedule.nextOccurrence(SEVEN, EVERY_DAY,
                ReminderSchedule.Recurrence.DAILY, zone.getId(), from).orElseThrow();
    }

    @Nested
    @DisplayName("the reference instant decides which occurrence is next")
    class ReferenceInstant {

        @Test
        @DisplayName("before today's reminder, today's occurrence is next and the delay is positive")
        void beforeTodaysReminderReturnsToday() {
            // 00:00Z is 05:30 IST, so 07:00 local is still ahead and stays on the same day.
            Instant from = Instant.parse("2026-09-29T00:00:00Z");
            Instant next = daily(from, KOLKATA);

            assertThat(next).as("2026-09-29 07:00 IST is 2026-09-29T01:30Z")
                    .isEqualTo(Instant.parse("2026-09-29T01:30:00Z"));
            assertThat(next).isAfter(from);
            assertThat(Duration.between(from, next).toMinutes()).as("a positive delay").isEqualTo(90);
        }

        @Test
        @DisplayName("after today's reminder, tomorrow's occurrence is next - never a negative delay")
        void afterTodaysReminderReturnsTomorrow() {
            // 2026-09-29 09:00 IST is 03:30Z, so 07:00 IST has already passed locally today.
            Instant from = Instant.parse("2026-09-29T03:30:00Z");
            Instant next = daily(from, KOLKATA);

            assertThat(next).as("today's 07:00 IST already elapsed, so it rolls to the next day")
                    .isEqualTo(Instant.parse("2026-09-30T01:30:00Z"));
            assertThat(next).as("never an already-passed occurrence").isAfter(from);
            assertThat(Duration.between(from, next).toMinutes())
                    .as("a positive delay, not a negative one").isPositive();
        }

        @Test
        @DisplayName("exactly at the occurrence, the next one is the following day, not this instant")
        void exactlyAtTheOccurrenceRollsForward() {
            Instant at = Instant.parse("2026-09-30T01:30:00Z");

            assertThat(daily(at, KOLKATA)).as("'next' is strictly after the reference")
                    .isEqualTo(Instant.parse("2026-10-01T01:30:00Z"));
        }

        @Test
        @DisplayName("one second before the occurrence, that occurrence is still next")
        void oneSecondBeforeTheOccurrence() {
            Instant justBefore = Instant.parse("2026-09-30T01:29:59Z");

            assertThat(daily(justBefore, KOLKATA))
                    .isEqualTo(Instant.parse("2026-09-30T01:30:00Z"));
        }
    }

    @Nested
    @DisplayName("a half-hour offset is carried exactly")
    class KolkataOffset {

        @Test
        @DisplayName("07:00 Asia/Kolkata is 01:30 UTC, five and a half hours ahead")
        void sevenAmIsHalfHourZone() {
            Instant next = daily(Instant.parse("2026-09-29T20:00:00Z"), KOLKATA);

            assertThat(next.atZone(ZoneOffset.UTC).toLocalTime())
                    .as("07:00 local is 01:30 UTC").isEqualTo(LocalTime.of(1, 30));
            assertThat(next.atZone(KOLKATA).getOffset().getTotalSeconds())
                    .as("+05:30, not truncated to whole hours").isEqualTo(19800);
        }

        @Test
        @DisplayName("two zones at the same local time can be 330 minutes apart on the same day")
        void sameLocalTimeDifferentZones() {
            Instant from = Instant.parse("2026-09-29T20:00:00Z");

            Instant kolkata = daily(from, KOLKATA);
            Instant utc = daily(from, ZoneOffset.UTC);

            assertThat(Duration.between(kolkata, utc).toMinutes()).isEqualTo(330);
        }

        @Test
        @DisplayName("but across a UTC day boundary the same pair is 24h apart, which is not a bug")
        void sameLocalTimeAcrossADayBoundary() {
            // 02:00Z is 07:30 IST: 07:00 has ALREADY passed locally in Kolkata, so it rolls to
            // tomorrow, while 07:00 UTC is still ahead today. The two land a day apart in UTC even
            // though both are the correct "next 07:00 local" for their own zone.
            Instant from = Instant.parse("2026-09-29T02:00:00Z");

            Instant kolkata = daily(from, KOLKATA);
            Instant utc = daily(from, ZoneOffset.UTC);

            assertThat(kolkata).as("Kolkata rolled to the next day")
                    .isEqualTo(Instant.parse("2026-09-30T01:30:00Z"));
            assertThat(utc).as("UTC is still on the same day")
                    .isEqualTo(Instant.parse("2026-09-29T07:00:00Z"));
            assertThat(Duration.between(kolkata, utc).toMinutes())
                    .as("330 less one whole day - the origin of the historical -1110")
                    .isEqualTo(330 - 1440);
        }
    }

    @Nested
    @DisplayName("the local wall-clock time survives a daylight-saving transition")
    class DaylightSaving {

        @Test
        @DisplayName("a 07:00 reminder stays at 07:00 local across the spring-forward hour")
        void staysAtSevenAcrossSpringForward() {
            ZoneId london = ZoneId.of("Europe/London");

            Instant before = daily(Instant.parse("2026-03-28T00:00:00Z"), london);
            Instant after = daily(Instant.parse("2026-03-29T00:00:00Z"), london);

            assertThat(before.atZone(london).toLocalTime())
                    .as("still 07:00 local, on GMT").isEqualTo(LocalTime.of(7, 0));
            assertThat(before).as("GMT is UTC+0").isEqualTo(Instant.parse("2026-03-28T07:00:00Z"));

            assertThat(after.atZone(london).toLocalTime())
                    .as("still 07:00 local, on BST").isEqualTo(LocalTime.of(7, 0));
            assertThat(after).as("BST is UTC+1, so the same local time is an hour earlier in UTC")
                    .isEqualTo(Instant.parse("2026-03-29T06:00:00Z"));
        }

        @Test
        @DisplayName("a zone with no daylight saving is unaffected by the transition")
        void kolkataHasNoDaylightSaving() {
            Instant march = daily(Instant.parse("2026-03-29T00:00:00Z"), KOLKATA);

            ZonedDateTime local = march.atZone(KOLKATA);
            assertThat(local.toLocalTime()).isEqualTo(LocalTime.of(7, 0));
            assertThat(local.getOffset().getTotalSeconds()).isEqualTo(19800);
        }
    }

    @Nested
    @DisplayName("a one-time reminder already in the past rolls to tomorrow")
    class OnceRecurrence {

        @Test
        @DisplayName("an elapsed one-time reminder is rescheduled rather than left behind")
        void onceRollsForward() {
            Instant from = Instant.parse("2026-09-29T10:00:00Z");

            Instant next = ReminderSchedule.nextOccurrence(SEVEN, EVERY_DAY,
                    ReminderSchedule.Recurrence.ONCE, KOLKATA.getId(), from).orElseThrow();

            assertThat(next).isAfter(from);
            assertThat(next.atZone(KOLKATA).toLocalTime()).isEqualTo(LocalTime.of(7, 0));
        }
    }
}