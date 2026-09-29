package com.fittrack.reminder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Quiet-hours window arithmetic (Phase 19).
 *
 * <p>Pure function tests with the instant pinned, so nothing here depends on the time of day the
 * suite happens to run at. The properties that matter are the two edges of an overnight window, the
 * half-open boundary, and daylight saving - which is where a wall-clock policy most easily goes
 * wrong.
 */
@DisplayName("quiet hours")
class QuietHoursTest {

    private static final LocalTime NIGHT_START = LocalTime.of(22, 0);
    private static final LocalTime NIGHT_END = LocalTime.of(7, 0);

    private static Instant at(String iso) { return Instant.parse(iso); }

    /** An instant built from a local wall-clock time in a zone, which is how a user means it. */
    private static Instant local(String zone, String date, String time) {
        return LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(ZoneId.of(zone)).toInstant();
    }

    @Nested
    @DisplayName("overnight window 22:00 -> 07:00")
    class Overnight {

        private final ZoneId kolkata = ZoneId.of("Asia/Kolkata");

        @Test
        @DisplayName("the evening before the start is not quiet")
        void beforeStart() {
            assertThat(QuietHours.isQuiet(local("Asia/Kolkata", "2026-01-15", "21:59:59"),
                    kolkata, NIGHT_START, NIGHT_END)).isFalse();
        }

        @Test
        @DisplayName("the start instant itself is quiet")
        void atStart() {
            // Half-open: [start, end). 22:00:00 exactly is inside, not outside.
            assertThat(QuietHours.isQuiet(local("Asia/Kolkata", "2026-01-15", "22:00:00"),
                    kolkata, NIGHT_START, NIGHT_END)).isTrue();
        }

        @Test
        @DisplayName("the small hours are quiet, across midnight")
        void afterMidnight() {
            assertThat(QuietHours.isQuiet(local("Asia/Kolkata", "2026-01-16", "02:30:00"),
                    kolkata, NIGHT_START, NIGHT_END)).isTrue();
        }

        @Test
        @DisplayName("the last quiet second is 06:59:59")
        void lastQuietSecond() {
            assertThat(QuietHours.isQuiet(local("Asia/Kolkata", "2026-01-16", "06:59:59"),
                    kolkata, NIGHT_START, NIGHT_END)).isTrue();
        }

        @Test
        @DisplayName("the end instant is already outside, so a deferred occurrence can be delivered")
        void atEnd() {
            // This is the property that stops a deferral looping forever: the instant a window closes
            // is not itself quiet, so an occurrence deferred to it is delivered on the next tick.
            assertThat(QuietHours.isQuiet(local("Asia/Kolkata", "2026-01-16", "07:00:00"),
                    kolkata, NIGHT_START, NIGHT_END)).isFalse();
        }

        @Test
        @DisplayName("the daytime is not quiet")
        void daytime() {
            assertThat(QuietHours.isQuiet(local("Asia/Kolkata", "2026-01-15", "12:00:00"),
                    kolkata, NIGHT_START, NIGHT_END)).isFalse();
        }
    }

    @Nested
    @DisplayName("same-day window 09:00 -> 17:00")
    class SameDay {

        private final ZoneId utc = ZoneOffset.UTC;

        @Test
        @DisplayName("before, inside and after the window")
        void boundaries() {
            assertThat(QuietHours.isQuiet(local("UTC", "2026-01-15", "08:59:59"),
                    utc, LocalTime.of(9, 0), LocalTime.of(17, 0))).isFalse();
            assertThat(QuietHours.isQuiet(local("UTC", "2026-01-15", "12:00:00"),
                    utc, LocalTime.of(9, 0), LocalTime.of(17, 0))).isTrue();
            assertThat(QuietHours.isQuiet(local("UTC", "2026-01-15", "17:00:00"),
                    utc, LocalTime.of(9, 0), LocalTime.of(17, 0))).isFalse();
        }

        @Test
        @DisplayName("a same-day window does not spill into the next morning")
        void doesNotSpill() {
            assertThat(QuietHours.isQuiet(local("UTC", "2026-01-16", "02:00:00"),
                    utc, LocalTime.of(9, 0), LocalTime.of(17, 0))).isFalse();
        }
    }

    @Nested
    @DisplayName("window end")
    class WindowEnd {

        @Test
        @DisplayName("an overnight occurrence resumes at the end of the window, in the user's zone")
        void resumesAtQuietEnd() {
            Instant due = local("Asia/Kolkata", "2026-01-16", "02:30:00");
            Instant resume = QuietHours.endOfWindow(due, ZoneId.of("Asia/Kolkata"), NIGHT_START, NIGHT_END)
                    .orElseThrow();

            assertThat(resume).isEqualTo(local("Asia/Kolkata", "2026-01-16", "07:00:00"));
            // The resume instant must itself be outside the window, or the next tick would defer again.
            assertThat(QuietHours.isQuiet(resume, ZoneId.of("Asia/Kolkata"), NIGHT_START, NIGHT_END))
                    .as("the resume instant is deliverable, so a deferral terminates")
                    .isFalse();
        }

        @Test
        @DisplayName("an occurrence outside quiet hours has no window to end")
        void noneWhenNotQuiet() {
            assertThat(QuietHours.endOfWindow(local("Asia/Kolkata", "2026-01-15", "12:00:00"),
                    ZoneId.of("Asia/Kolkata"), NIGHT_START, NIGHT_END)).isEmpty();
        }

        @Test
        @DisplayName("an evening occurrence resumes the next morning, not the same evening")
        void eveningResumesNextMorning() {
            Instant due = local("Asia/Kolkata", "2026-01-15", "23:00:00");
            Instant resume = QuietHours.endOfWindow(due, ZoneId.of("Asia/Kolkata"), NIGHT_START, NIGHT_END)
                    .orElseThrow();

            assertThat(resume).isEqualTo(local("Asia/Kolkata", "2026-01-16", "07:00:00"));
        }
    }

    @Nested
    @DisplayName("daylight saving")
    class DaylightSaving {

        /** Northern-hemisphere zone whose clocks jump forward at 02:00 on these dates. */
        private final ZoneId newYork = ZoneId.of("America/New_York");

        @Test
        @DisplayName("a spring-forward day resolves the window to real instants and one window only")
        void springForward() {
            // 2026-03-08 is the US spring-forward date. A 22:00->07:00 window spans the transition.
            Instant due = LocalDate.parse("2026-03-08").atTime(LocalTime.of(23, 0))
                    .atZone(newYork).toInstant();

            Instant resume = QuietHours.endOfWindow(due, newYork, NIGHT_START, NIGHT_END).orElseThrow();

            // The window ends at 07:00 local time on the following date. Asserted in local terms
            // deliberately: the UTC instant differs by an hour either side of a transition, and
            // pinning it would re-break this test at the next DST change.
            ZonedDateTime localEnd = resume.atZone(newYork);
            assertThat(localEnd.toLocalTime()).isEqualTo(LocalTime.of(7, 0));
            assertThat(localEnd.toLocalDate()).isEqualTo(LocalDate.parse("2026-03-09"));
        }

        @Test
        @DisplayName("a window spanning a spring-forward still contains the real instants after it")
        void quietAcrossTransition() {
            // 02:30 does not exist on the spring-forward date. The window must still contain the
            // surrounding real instants rather than throwing or resolving to a non-existent time.
            Instant justAfter = LocalDate.parse("2026-03-08").atTime(LocalTime.of(3, 0))
                    .atZone(newYork).toInstant();

            assertThat(QuietHours.isQuiet(justAfter, newYork, NIGHT_START, NIGHT_END)).isTrue();
            assertThat(QuietHours.endOfWindow(justAfter, newYork, NIGHT_START, NIGHT_END).orElseThrow())
                    .as("a deferral across a transition still resumes in the future")
                    .isAfter(justAfter);
        }

        @Test
        @DisplayName("a fall-back day is quiet and resumes at the local end time")
        void fallBack() {
            // 2026-11-01 is the US fall-back date, when 01:00-02:00 local occurs twice.
            Instant due = LocalDate.parse("2026-11-01").atTime(LocalTime.of(1, 30))
                    .atZone(newYork).toInstant();

            Instant resume = QuietHours.endOfWindow(due, newYork, NIGHT_START, NIGHT_END).orElseThrow();

            assertThat(resume.atZone(newYork).toLocalTime()).isEqualTo(LocalTime.of(7, 0));
        }

        @Test
        @DisplayName("the same wall-clock time is a different instant in two zones")
        void zoneChangesTheInstant() {
            // 22:00 in Kolkata is quiet; the same instant is mid-afternoon in New York. Resolving in
            // the user's zone rather than a fixed UTC offset is what makes this correct.
            Instant at22Kolkata = local("Asia/Kolkata", "2026-01-15", "22:00:00");
            assertThat(QuietHours.isQuiet(at22Kolkata, ZoneId.of("Asia/Kolkata"), NIGHT_START, NIGHT_END))
                    .isTrue();
            assertThat(QuietHours.isQuiet(at22Kolkata, newYork, NIGHT_START, NIGHT_END))
                    .as("the same instant is mid-afternoon in New York")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("degenerate input")
    class Degenerate {

        @Test
        @DisplayName("start == end resolves to a full day rather than an empty or infinite window")
        void startEqualsEnd() {
            // The API rejects this, so it is only reachable via a hand-edited row. The conservative
            // reading is "quiet all day", which errs toward not disturbing the user.
            assertThat(QuietHours.isQuiet(at("2026-01-15T12:00:00Z"), ZoneOffset.UTC,
                    LocalTime.of(9, 0), LocalTime.of(9, 0))).isTrue();
        }
    }
}
