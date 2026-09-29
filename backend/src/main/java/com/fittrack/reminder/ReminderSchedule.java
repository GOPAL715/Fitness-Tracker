package com.fittrack.reminder;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * Timezone-aware next-occurrence calculation.
 *
 * <p>Every computation resolves the reminder's wall-clock time in the <em>user's</em> zone and
 * converts to an instant afterwards. The server's default zone is never consulted, so a reminder
 * created for Asia/Kolkata fires at the same instant regardless of where the backend runs.
 */
public final class ReminderSchedule {

    /** How often a reminder repeats. Mirrors the existing {@code days_of_week} column. */
    public enum Recurrence {
        ONCE, DAILY, WEEKLY;

        public static Recurrence parse(String raw) {
            if (raw == null) return DAILY;
            try {
                return valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return DAILY;
            }
        }
    }

    private ReminderSchedule() {}

    /** Parses a stored IANA zone id, falling back to UTC only when the value is unusable. */
    public static ZoneId zone(String id) {
        if (id == null || id.isBlank()) return ZoneId.of("UTC");
        try {
            return ZoneId.of(id.trim());
        } catch (RuntimeException e) {
            return ZoneId.of("UTC");
        }
    }

    /**
     * First occurrence strictly after {@code from}.
     *
     * @param from the instant to search forward from, normally "now"
     * @return the next occurrence, or empty when a one-time reminder is already in the past
     */
    public static java.util.Optional<Instant> nextOccurrence(
            LocalTime time, String daysOfWeek, Recurrence recurrence, String zoneId, Instant from) {

        ZoneId zone = zone(zoneId);
        ZonedDateTime fromLocal = from.atZone(zone);

        if (recurrence == Recurrence.ONCE) {
            ZonedDateTime candidate = fromLocal.toLocalDate().atTime(time).atZone(zone);
            if (!candidate.toInstant().isAfter(from)) {
                candidate = fromLocal.toLocalDate().plusDays(1).atTime(time).atZone(zone);
            }
            return java.util.Optional.of(candidate.toInstant());
        }

        if (recurrence == Recurrence.DAILY) {
            ZonedDateTime candidate = fromLocal.toLocalDate().atTime(time).atZone(zone);
            while (!candidate.toInstant().isAfter(from)) candidate = candidate.plusDays(1);
            return java.util.Optional.of(candidate.toInstant());
        }

        // WEEKLY: honour the existing comma/space separated day-of-week list.
        Set<DayOfWeek> days = parseDays(daysOfWeek);
        if (days.isEmpty()) days = EnumSet.allOf(DayOfWeek.class);

        for (int offset = 0; offset <= 7; offset++) {
            LocalDate date = fromLocal.toLocalDate().plusDays(offset);
            if (!days.contains(date.getDayOfWeek())) continue;
            ZonedDateTime candidate = date.atTime(time).atZone(zone);
            if (candidate.toInstant().isAfter(from)) return java.util.Optional.of(candidate.toInstant());
        }
        throw new IllegalStateException("weekly schedule produced no occurrence");
    }

    /** Parses a stored {@code days_of_week} value; accepts names, indices and JSON-ish lists. */
    static Set<DayOfWeek> parseDays(String raw) {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        if (raw == null || raw.isBlank()) return days;
        String cleaned = raw.replace("[", "").replace("]", "").replace("\"", "");
        for (String token : cleaned.split("[,;\\s]+")) {
            if (token.isBlank()) continue;
            DayOfWeek day = toDay(token);
            if (day != null) days.add(day);
        }
        return days;
    }

    private static DayOfWeek toDay(String token) {
        String value = token.trim();
        if (value.isEmpty()) return null;
        if (value.chars().allMatch(Character::isDigit)) {
            return fromIndex(value);
        }
        try {
            return DayOfWeek.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Maps a stored day index to a weekday.
     *
     * <p>The index contract is {@code 0 = Sunday} through {@code 6 = Saturday}, which is the numbering
     * the frontend has always used. {@code 7} is also accepted as Sunday, because
     * {@link DayOfWeek#SUNDAY} is numerically 7 in java.time and a value stored under either
     * convention should still resolve.
     *
     * <p>This mapping is the fix for a defect found during the Phase 16 audit. The previous code
     * passed the index straight to {@link DayOfWeek#of(int)}, which is {@code 1 = Monday ...
     * 7 = Sunday}, so every index shifted by one and {@code 0} threw outright: a weekly reminder
     * containing Sunday failed to reschedule. Existing rows written as {@code "1,2,3,4,5,6,0"}
     * therefore meant Monday-to-Saturday plus a crash, and are now read the way the UI has always
     * displayed them. Out-of-range values are ignored rather than fatal, so one bad token cannot take
     * down a whole schedule.
     */
    private static DayOfWeek fromIndex(String token) {
        int index;
        try {
            index = Integer.parseInt(token);
        } catch (NumberFormatException e) {
            return null;
        }
        if (index == 0 || index == 7) return DayOfWeek.SUNDAY;
        if (index >= 1 && index <= 6) return DayOfWeek.of(index);
        return null;
    }
}
