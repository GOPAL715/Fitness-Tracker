package com.fittrack.reminder;

import com.fittrack.health.UserTimezone;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * User-level notification preferences.
 *
 * <p>Every method takes the authenticated user id and the ownership predicate is part of the query
 * itself, so a missing scope cannot be forgotten at a call site - the same discipline
 * {@code PushSubscriptionService} already follows.
 *
 * <p><b>Not stored here, ever:</b> browser permission, push subscriptions, or delivery state. A
 * preference is what the user wants attempted; the ledger records what happened; the browser owns
 * its own permission. Conflating them is what makes a notification screen untrustworthy.
 */
@Service
public class NotificationPreferencesService {

    private final JdbcTemplate jdbc;

    public NotificationPreferencesService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One user's stored preferences, or the defaults when the user has never written any.
     *
     * <p>The defaults are returned rather than a row being lazily inserted on read: a GET must not
     * write, and a user who never opens the settings screen should not accumulate a row.
     */
    public record Preferences(boolean pushEnabled, boolean reminderNotificationsEnabled,
                              boolean quietHoursEnabled, LocalTime quietHoursStart, LocalTime quietHoursEnd,
                              String timezone) {

        /**
         * Whether a reminder may be delivered to this user at all, ignoring the clock.
         *
         * <p>Both switches must be on. They are separate preferences so each is independently
         * reportable, but neither alone is sufficient to deliver.
         */
        public boolean allowsDelivery() {
            return pushEnabled && reminderNotificationsEnabled;
        }

        /** The defaults: reminders on, no quiet hours, no zone. */
        public static Preferences defaults() {
            return new Preferences(true, true, false, null, null, null);
        }
    }

    /**
     * The caller's preferences, defaulted when no row exists.
     *
     * <p>An unreadable row is a defect rather than a reason to silence a user, so this never
     * swallows a database failure into "defaults" - that would silently stop delivering reminders.
     */
    public Preferences forUser(UUID userId) {
        List<Preferences> rows = jdbc.query(
                "select push_enabled,reminder_notifications_enabled,quiet_hours_enabled,"
                        + "quiet_hours_start,quiet_hours_end,timezone"
                        + " from user_notification_preferences where user_id=CAST(? AS uuid)",
                (rs, i) -> map(rs), userId);
        return rows.isEmpty() ? Preferences.defaults() : rows.get(0);
    }

    /**
     * Stores the caller's preferences, creating the row on first write.
     *
     * <p>Upsert rather than insert-then-update so two concurrent saves cannot both miss and then
     * collide. The write is scoped by {@code user_id} in the conflict target, so a body carrying
     * another user's id is ignored on that field exactly as the push subscription route does.
     */
    @Transactional
    public Preferences save(UUID userId, Preferences preferences) {
        jdbc.update("insert into user_notification_preferences"
                        + "(user_id,push_enabled,reminder_notifications_enabled,quiet_hours_enabled,"
                        + "quiet_hours_start,quiet_hours_end,timezone,updated_at)"
                        + " values (?,?,?,?,?,?,?,now())"
                        + " on conflict (user_id) do update set"
                        + " push_enabled=excluded.push_enabled,"
                        + " reminder_notifications_enabled=excluded.reminder_notifications_enabled,"
                        + " quiet_hours_enabled=excluded.quiet_hours_enabled,"
                        + " quiet_hours_start=excluded.quiet_hours_start,"
                        + " quiet_hours_end=excluded.quiet_hours_end,"
                        + " timezone=excluded.timezone,"
                        + " updated_at=now()",
                userId,
                preferences.pushEnabled(),
                preferences.reminderNotificationsEnabled(),
                preferences.quietHoursEnabled(),
                sqlTime(preferences.quietHoursStart()),
                sqlTime(preferences.quietHoursEnd()),
                preferences.timezone());
        return forUser(userId);
    }

    /**
     * Validates and canonicalises a submitted preference set.
     *
     * <p>Rejects rather than repairs. A silently-substituted timezone would quietly misplace every
     * quiet-hours decision, and a {@code start == end} window has no defensible reading, so both
     * are refused instead of being turned into a guess the user never asked for.
     *
     * @throws IllegalArgumentException with a message safe to show the user
     */
    public static Preferences validate(Boolean pushEnabled, Boolean reminderNotificationsEnabled,
                                      Boolean quietHoursEnabled, String quietHoursStart,
                                      String quietHoursEnd, String timezone) {
        Preferences defaults = Preferences.defaults();
        boolean quiet = Boolean.TRUE.equals(quietHoursEnabled);

        String zone = null;
        if (timezone != null && !timezone.isBlank()) {
            zone = UserTimezone.canonical(timezone);
            if (zone == null) {
                throw new IllegalArgumentException("timezone must be a valid IANA zone id");
            }
        }

        LocalTime start = null;
        LocalTime end = null;
        if (quiet) {
            // A zone is required, not defaulted. Quiet hours are a wall-clock window, and resolving
            // one without knowing the clock it belongs to would be a guess about the user's night.
            if (zone == null) {
                throw new IllegalArgumentException("quiet hours require a timezone");
            }
            start = time(quietHoursStart, "quietHoursStart");
            end = time(quietHoursEnd, "quietHoursEnd");
            if (start.equals(end)) {
                // Ambiguous between "no quiet hours" and "all day". Refused rather than guessed.
                throw new IllegalArgumentException("quietHoursStart and quietHoursEnd must differ");
            }
        }

        return new Preferences(
                pushEnabled == null ? defaults.pushEnabled() : pushEnabled,
                reminderNotificationsEnabled == null
                        ? defaults.reminderNotificationsEnabled() : reminderNotificationsEnabled,
                quiet, start, end, zone);
    }

    private static LocalTime time(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(field + " is required when quiet hours are enabled");
        }
        try {
            return LocalTime.parse(raw.trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(field + " must be a valid HH:mm time");
        }
    }

    private static java.sql.Time sqlTime(LocalTime value) {
        return value == null ? null : java.sql.Time.valueOf(value);
    }

    private static Preferences map(ResultSet rs) throws SQLException {
        java.sql.Time start = rs.getTime("quiet_hours_start");
        java.sql.Time end = rs.getTime("quiet_hours_end");
        return new Preferences(
                rs.getBoolean("push_enabled"),
                rs.getBoolean("reminder_notifications_enabled"),
                rs.getBoolean("quiet_hours_enabled"),
                start == null ? null : start.toLocalTime(),
                end == null ? null : end.toLocalTime(),
                rs.getString("timezone"));
    }
}
