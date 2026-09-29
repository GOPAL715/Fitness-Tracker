package com.fittrack.reminder;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs the existing reminder delivery pipeline on a timer.
 *
 * <p>Nothing about delivery is decided here. What is due comes from
 * {@link ReminderDeliveryService#dueReminders}; whether an occurrence may be delivered, how the
 * channel is called and how retries are budgeted stay in {@link ReminderDeliveryService}; the
 * following occurrence is computed by {@link ReminderSchedule}. This class owns the one thing that
 * was missing: invoking that machinery on a poll instead of never. It holds no SQL, no retry loop
 * and no schedule arithmetic.
 *
 * <h2>Time</h2>
 * The reference instant is read once per tick from the injected {@link Clock} - the same collaborator
 * {@link ReminderController} takes - so every reminder in a tick is judged against one instant and an
 * occurrence cannot slide across the boundary halfway through a pass. Nothing here reads the wall
 * clock statically, which is what lets a test pin the instant instead of inheriting the time of day
 * the suite happens to run at. Production supplies {@link Clock#systemUTC()} through
 * {@link ReminderClockConfig}, exactly as before.
 * <h2>Ordering: deliver first, advance second</h2>
 * The occurrence is advanced only after it has been processed. If the process died in between, the
 * next pass finds the same occurrence due again and the claim inside
 * {@link ReminderDeliveryService#deliver} reports it as a duplicate rather than send a second
 * notification. Advancing first would lose the occurrence outright. A {@code failed} or
 * {@code exhausted} occurrence still advances the schedule, because the delivery service has already
 * decided that retry budget is spent: offering it again every tick is precisely the unbounded retry
 * that service deliberately avoids.
 *
 * <h2>Overlap and multi-instance safety</h2>
 * {@code tickInProgress} keeps one instance from starting a second tick while its own first tick is
 * still inside a slow provider. Spring's default task scheduler is single-threaded, so today a tick
 * cannot overlap another, but that is a property of the executor rather than something this class may
 * assume: raising {@code spring.task.scheduling.pool.size}, or pairing a long provider timeout with a
 * short interval, is enough to break it. The flag keeps the invariant local and explicit.
 *
 * <p><strong>That guard is deliberately not a distributed lock and must not be mistaken for one.</strong>
 * It only ever sees its own JVM. Two instances - or a restart while a tick is in flight - are made
 * safe by the guarantee that already exists: {@code reminder_deliveries} has a unique key on
 * {@code (reminder_id, occurrence_at)} and an occurrence is claimed with an atomic insert, so the
 * loser of the race returns {@code duplicate} without reaching the provider. That constraint remains
 * the final protection against duplicate occurrence accounting, and this scheduler changes nothing
 * about it. No Redis lock was added: the uniqueness constraint already gives the guarantee, so a
 * second coordination layer would only add a failure mode.
 */
@Component
public class ReminderDeliveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReminderDeliveryScheduler.class);

    private final ReminderDeliveryService delivery;
    private final Clock clock;
    private final boolean enabled;
    private final String interval;
    private final AtomicBoolean tickInProgress = new AtomicBoolean();

    public ReminderDeliveryScheduler(ReminderDeliveryService delivery, Clock clock,
            @Value("${app.reminders.scheduler.enabled:true}") boolean enabled,
            @Value("${app.reminders.scheduler.interval:PT60S}") String interval) {
        this.delivery = delivery;
        this.clock = clock;
        this.enabled = enabled;
        this.interval = interval;
    }

    /**
     * Reports which state the subsystem booted in.
     *
     * <p>Logged once at startup rather than on every poll: "the scheduler is off" is a configuration
     * fact an operator needs precisely when reminders did not arrive, and repeating it each interval
     * would bury the lines that matter. The channel is included because with no provider registered
     * delivery is intentionally unavailable, and that distinction belongs in the startup record. No
     * credential or destination is ever logged - {@link NotificationDeliveryProvider#channel()} is a
     * label for exactly this kind of line.
     */
    @PostConstruct
    void reportConfiguration() {
        if (enabled) {
            log.info("reminder_scheduler_started enabled=true interval={} channel={}",
                    interval, delivery.channel());
        } else {
            log.info("reminder_scheduler_disabled enabled=false interval={} reason=configuration", interval);
        }
    }

    /** Whether this instance polls. Exposed because the flag is configuration, not a constant. */
    public boolean enabled() { return enabled; }

    /**
     * One poll. Also the entry point a test drives directly, which is how the behaviour is verified
     * without depending on how fast the machine running it happens to be.
     *
     * <p>The first execution waits one full interval after startup, so a process does not work
     * through a backlog while its connections and provider are still coming up.
     */
    @Scheduled(fixedDelayString = "${app.reminders.scheduler.interval:PT60S}",
            initialDelayString = "${app.reminders.scheduler.interval:PT60S}")
    public void tick() {
        if (!enabled) return;

        if (!tickInProgress.compareAndSet(false, true)) {
            log.info("reminder_scheduler_tick_skipped reason=previous_tick_in_progress interval={}", interval);
            return;
        }
        try {
            runTick();
        } catch (RuntimeException e) {
            // The due query, the database, or a defect in this class. Loud, because a scheduler that
            // has stopped working is indistinguishable from one that found nothing to do; and never
            // rethrown, because an exception escaping a scheduled method cancels its future runs.
            log.error("reminder_scheduler_tick_failed exception_type={} interval={}",
                    e.getClass().getSimpleName(), interval, e);
        } finally {
            tickInProgress.set(false);
        }
    }

    /**
     * Processes every reminder due at the tick instant.
     *
     * <p>One occurrence failing never ends the pass. {@link ReminderDeliveryService#deliver} already
     * contains a provider that throws, so what can reach this loop is a per-row problem - an unusable
     * stored time, a connection dropped mid-pass. Discarding the rest of the queue because of one
     * reminder's bad row would turn a single defect into an outage, so each failure is logged and its
     * reminder is simply left due for the next tick to try again.
     */
    private void runTick() {
        Instant now = clock.instant();
        List<Map<String, Object>> due = delivery.dueReminders(now);
        if (due.isEmpty()) {
            log.debug("reminder_scheduler_tick now={} due=0", now);
            return;
        }

        log.info("reminder_scheduler_tick_started now={} due={}", now, due.size());
        int processed = 0;
        int failed = 0;
        for (Map<String, Object> reminder : due) {
            try {
                Instant occurrence = occurrence(reminder);
                ReminderDeliveryService.DeliveryResult result = deliverAndAdvance(reminder, now, occurrence);
                processed++;
                log.info("reminder_delivery_attempted reminder_id={} occurrence={} state={} attempts={}",
                        result.reminderId(), occurrence, result.state(), result.attempts());
            } catch (RuntimeException e) {
                failed++;
                log.warn("reminder_scheduler_occurrence_failed reminder_id={} exception_type={}",
                        idOf(reminder), e.getClass().getSimpleName(), e);
            }
        }
        log.info("reminder_scheduler_tick_completed now={} due={} processed={} failed={} elapsed_ms={}",
                now, due.size(), processed, failed,
                Math.max(0, Duration.between(now, clock.instant()).toMillis()));
    }

    /**
     * Delivers one occurrence through the existing pipeline, then reschedules the reminder.
     *
     * <p>Neither step runs inside a wider transaction of its own: {@link ReminderDeliveryService}
     * commits the claim and the outcome in their own short transactions precisely so a provider call
     * never holds a database connection and transaction open, and the tick leaves that alone.
     *
     * @param now the tick's reference instant, which is what the next occurrence is measured from, so
     *        one pass is internally consistent and a long backlog collapses to a single upcoming
     *        occurrence instead of a delivery storm of everything that was missed
     */
    private ReminderDeliveryService.DeliveryResult deliverAndAdvance(Map<String, Object> reminder,
            Instant now, Instant occurrence) {
        UUID reminderId = uuid(reminder.get("id"));
        UUID userId = uuid(reminder.get("user_id"));

        // Phase 19: the user policy is consulted before anything is claimed or sent, so a decision
        // not to notify is never recorded as a delivery failure and never consumes the retry budget.
        ReminderNotificationPolicy.Outcome policy = delivery.policy(userId, now);

        if (policy.decision() == ReminderNotificationPolicy.Decision.DEFER) {
            // Quiet hours. The occurrence is not lost and not claimed: the schedule moves to the
            // instant the window closes, so the very next tick delivers this same occurrence with
            // its original occurrence_at intact. Claiming it here would burn the unique key and the
            // occurrence could never be delivered at all.
            delivery.updateNextOccurrence(reminderId, policy.until());
            return new ReminderDeliveryService.DeliveryResult(reminderId, "deferred", 0, false);
        }

        if (policy.decision() == ReminderNotificationPolicy.Decision.SUPPRESS) {
            // Notifications are off for this user. The occurrence is finished rather than postponed,
            // so it is recorded as skipped and the schedule advances as it would after any outcome.
            delivery.recordSuppressed(reminderId, userId, occurrence);
            advance(reminder, now);
            return new ReminderDeliveryService.DeliveryResult(
                    reminderId, ReminderNotificationPolicy.SKIPPED_POLICY, 0, false);
        }

        ReminderDeliveryService.DeliveryResult result = delivery.deliver(
                reminderId, userId,
                text(reminder.get("title")), text(reminder.get("message")), occurrence);
        advance(reminder, now);
        return result;
    }

    /** Advances the schedule to the occurrence after the tick instant. */
    private void advance(Map<String, Object> reminder, Instant now) {
        UUID reminderId = uuid(reminder.get("id"));
        Instant next = ReminderSchedule.nextOccurrence(
                time(reminder.get("scheduled_time")),
                text(reminder.get("days_of_week")),
                ReminderSchedule.Recurrence.parse(text(reminder.get("recurrence"))),
                text(reminder.get("timezone")),
                now).orElse(null);
        delivery.updateNextOccurrence(reminderId, next);
    }

    /**
     * The reminder's wall-clock time of day.
     *
     * <p>The stored column is a SQL {@code time}, which the driver hands back as {@link
     * java.sql.Time}; that is read through {@code toLocalTime()} rather than stringified, because the
     * schedule is a local time of day resolved later in the reminder's own zone.
     */
    private static LocalTime time(Object value) {
        if (value instanceof LocalTime localTime) return localTime;
        if (value instanceof java.sql.Time sqlTime) return sqlTime.toLocalTime();
        return LocalTime.parse(String.valueOf(value));
    }

    /** The occurrence being processed, exactly as stored - never recomputed from the tick instant. */
    private static Instant occurrence(Map<String, Object> reminder) {
        Object stored = reminder.get("next_occurrence_at");
        if (stored instanceof Timestamp timestamp) return timestamp.toInstant();
        if (stored instanceof Instant instant) return instant;
        throw new IllegalStateException("due reminder row carries no usable next_occurrence_at");
    }

    private static UUID uuid(Object value) {
        if (value instanceof UUID id) return id;
        if (value != null) return UUID.fromString(String.valueOf(value));
        throw new IllegalStateException("reminder row is missing its id");
    }

    /** Best-effort id for a failure line; a malformed row must not mask the failure it reports. */
    private static UUID idOf(Map<String, Object> reminder) {
        try {
            return uuid(reminder.get("id"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Null-safe column text: an absent title or message stays null rather than becoming "null". */
    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
