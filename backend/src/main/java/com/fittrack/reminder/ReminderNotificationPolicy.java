package com.fittrack.reminder;

import com.fittrack.reminder.NotificationPreferencesService.Preferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

/**
 * Whether a due occurrence may be delivered right now, as a user policy.
 *
 * <p>This sits between the scheduler and delivery. It answers one question - "may this occurrence be
 * attempted at this instant for this user" - and it answers it <em>before</em> anything is claimed or
 * sent, so a suppression is never mistaken for a delivery failure and never consumes the occurrence
 * ledger.
 *
 * <h2>Why suppression is not a failure</h2>
 * A user who turned notifications off, or whose quiet hours are running, has not had anything go
 * wrong. Nothing failed, nothing was retried, nothing is exhausted, and no provider was ever
 * contacted. Recording that as {@code NO_SUBSCRIPTION} or {@code TEMPORARY_PROVIDER_ERROR} would
 * tell the user their device is broken when it is working exactly as they asked - and would consume
 * the retry budget for a decision that is not transient.
 *
 * <h2>Deferral versus suppression</h2>
 * These are different and are handled differently, which is the point of separating them:
 * <ul>
 *   <li><b>Quiet hours</b> defer: the occurrence is still wanted, just not now. It is rescheduled to
 *       the instant the window closes and stays unclaimed, so the same occurrence is delivered once,
 *       later, with its original {@code occurrence_at} intact.</li>
 *   <li><b>A disabled preference</b> suppresses: the occurrence is not wanted at all. The schedule
 *       still advances normally, so the reminder is not stuck re-presenting the same occurrence
 *       every tick, and the skip is recorded in the ledger as its own state.</li>
 * </ul>
 */
@Service
public class ReminderNotificationPolicy {

    private static final Logger log = LoggerFactory.getLogger(ReminderNotificationPolicy.class);

    private final NotificationPreferencesService preferences;

    public ReminderNotificationPolicy(NotificationPreferencesService preferences) {
        this.preferences = preferences;
    }

    /**
     * The ledger state for an occurrence deliberately not sent.
     *
     * <p>Its own value, distinct from every Phase 17 failure state. A user turning notifications off
     * is not a provider fault, so this can never be confused with one - and it carries no
     * {@code failure_category}, because there is no failure to categorise.
     */
    public static final String SKIPPED_POLICY = "skipped_policy";

    /** What the policy decided for one occurrence. */
    public enum Decision {
        /** Deliver now. */
        ALLOW,
        /** Not wanted at all; advance the schedule and record the skip. */
        SUPPRESS,
        /** Wanted, but not now; reschedule to {@link Outcome#until()} and send nothing. */
        DEFER
    }

    /**
     * The decision plus the instant a deferral should resume at.
     *
     * @param until the instant a DEFER becomes deliverable; null for ALLOW and SUPPRESS
     */
    public record Outcome(Decision decision, Instant until) {
        static Outcome allow() { return new Outcome(Decision.ALLOW, null); }
        static Outcome suppress() { return new Outcome(Decision.SUPPRESS, null); }
        static Outcome deferUntil(Instant until) { return new Outcome(Decision.DEFER, until); }
    }

    /**
     * Decides whether this occurrence may be delivered at {@code now}.
     *
     * <p>Order matters. The switches are checked before the clock, because a user who turned
     * notifications off should not have their reminders quietly reshuffled by a quiet-hours window
     * they can no longer change from the settings screen.
     */
    public Outcome evaluate(UUID userId, Instant now) {
        Preferences preferences = this.preferences.forUser(userId);

        if (!preferences.allowsDelivery()) {
            log.info("reminder_delivery_suppressed reminder_policy=preference_disabled");
            return Outcome.suppress();
        }

        if (preferences.quietHoursEnabled()) {
            // Validation guarantees both ends and a real zone are present together, so this is
            // unreachable in practice; the guard keeps a hand-edited row from throwing mid-tick.
            ZoneId zone = ReminderSchedule.zone(preferences.timezone());
            LocalTime start = preferences.quietHoursStart();
            LocalTime end = preferences.quietHoursEnd();
            if (start != null && end != null) {
                Optional<Instant> resume = QuietHours.endOfWindow(now, zone, start, end);
                if (resume.isPresent()) {
                    log.info("reminder_delivery_deferred until={} quiet_until={}", now, resume.get());
                    return Outcome.deferUntil(resume.get());
                }
            }
        }

        return Outcome.allow();
    }
}
