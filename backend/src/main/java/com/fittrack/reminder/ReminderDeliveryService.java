package com.fittrack.reminder;

import com.fittrack.reminder.NotificationDeliveryProvider.Outcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Delivers due reminders exactly once per scheduled occurrence.
 *
 * <p>Idempotency is enforced in PostgreSQL: {@code reminder_deliveries} has a unique key on
 * {@code (reminder_id, occurrence_at)} and the row is claimed with an atomic insert. A second
 * scheduler pass for the same occurrence - whether from a duplicate tick or a second instance -
 * loses the insert race and returns without calling the provider. No in-memory guard is relied on.
 */
@Service
public class ReminderDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(ReminderDeliveryService.class);

    private final JdbcTemplate jdbc;
    private final NotificationDeliveryProvider provider;
    private final int maxAttempts;

    public ReminderDeliveryService(JdbcTemplate jdbc, NotificationDeliveryProvider provider,
            @org.springframework.beans.factory.annotation.Value("${app.reminders.max-attempts:3}") int maxAttempts) {
        this.jdbc = jdbc;
        this.provider = provider;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    /** What happened to one occurrence. */
    public record DeliveryResult(UUID reminderId, String state, int attempts, boolean delivered) {}

    /**
     * Attempts delivery for one occurrence.
     *
     * <p>The claim and the terminal state change are committed in their own transactions so the
     * outcome survives a failure part-way through, and so a crash after the provider call cannot
     * cause a second delivery attempt on the next tick.
     */
    public DeliveryResult deliver(UUID reminderId, UUID userId, String title, String message, Instant occurrence) {
        if (!claim(reminderId, userId, occurrence)) {
            log.info("reminder_delivery_skipped reminder_id={} occurrence={} reason=already_claimed", reminderId, occurrence);
            return new DeliveryResult(reminderId, "duplicate", 0, false);
        }

        int attempts = 0;
        String lastError = "temporary";

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            attempts = attempt;
            Outcome outcome;
            try {
                outcome = provider.deliver(
                        new NotificationDeliveryProvider.DeliveryRequest(reminderId, userId, title, message, occurrence));
            } catch (RuntimeException e) {
                // An exploding provider is treated as transient; it must not escape the loop.
                outcome = Outcome.TEMPORARY_FAILURE;
                lastError = "provider_error";
            }

            if (outcome == Outcome.DELIVERED) {
                finish(reminderId, occurrence, "delivered", attempts, null);
                return new DeliveryResult(reminderId, "delivered", attempts, true);
            }
            if (outcome == Outcome.PERMANENT_FAILURE) {
                // Permanent failures are never retried.
                finish(reminderId, occurrence, "failed", attempts, "permanent");
                return new DeliveryResult(reminderId, "failed", attempts, false);
            }
        }

        // Retry budget exhausted: the occurrence is closed, not retried forever.
        finish(reminderId, occurrence, "exhausted", attempts, lastError);
        return new DeliveryResult(reminderId, "exhausted", attempts, false);
    }

    /**
     * Atomically claims the occurrence.
     *
     * @return true when this caller owns the delivery, false when it was already claimed
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean claim(UUID reminderId, UUID userId, Instant occurrence) {
        try {
            jdbc.update("insert into reminder_deliveries(id,reminder_id,user_id,occurrence_at,state,attempts)"
                            + " values (?,?,?,?,'pending',0)",
                    UUID.randomUUID(), reminderId, userId, Timestamp.from(occurrence));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void finish(UUID reminderId, Instant occurrence, String state, int attempts, String lastError) {
        jdbc.update("update reminder_deliveries set state=?,attempts=?,last_error=?,delivered_at=?"
                        + " where reminder_id=? and occurrence_at=?",
                state, attempts, lastError,
                "delivered".equals(state) ? Timestamp.from(Instant.now()) : null,
                reminderId, Timestamp.from(occurrence));
        jdbc.update("update reminders set delivery_status=?,delivery_attempts=?,last_error=?,"
                        + " last_delivered_at=case when ?='delivered' then now() else last_delivered_at end"
                        + " where id=?",
                state, attempts, lastError, state, reminderId);
    }

    /**
     * Reminders whose next occurrence is due, oldest first.
     *
     * <p>{@code next_occurrence_at} is part of the projection because it <em>is</em> the occurrence
     * being delivered: a caller cannot invoke {@link #deliver} without naming the instant it is
     * delivering, and re-querying it separately would allow two different instants to be in play
     * inside one pass.
     */
    public List<Map<String, Object>> dueReminders(Instant now) {
        return jdbc.queryForList("select id,user_id,title,message,timezone,recurrence,days_of_week,"
                        + "scheduled_time,next_occurrence_at"
                        + " from reminders where enabled=true and next_occurrence_at is not null"
                        + " and next_occurrence_at<=? order by next_occurrence_at",
                Timestamp.from(now));
    }

    /**
     * Stores the occurrence a subsequent pass should act on, or closes the reminder when {@code
     * nextOccurrence} is null.
     *
     * <p>This lives here rather than in the scheduler so every reminder statement stays in this
     * service: the scheduler decides when to run, it does not own SQL. The value is computed by the
     * caller through {@link ReminderSchedule#nextOccurrence}, which is the same calculation the
     * rescheduling endpoint already uses - there is no second schedule implementation.
     *
     * <p>The write is idempotent, so two instances that raced through the same occurrence and both
     * computed the following one simply store the same instant. Duplicate <em>accounting</em> is
     * prevented by the unique key on {@code reminder_deliveries}, never by this method.
     *
     * @param nextOccurrence the next instant to fire at, or null to stop scheduling the reminder
     */
    @Transactional
    public void updateNextOccurrence(UUID reminderId, Instant nextOccurrence) {
        if (nextOccurrence == null) {
            jdbc.update("update reminders set next_occurrence_at=null where id=?", reminderId);
            return;
        }
        jdbc.update("update reminders set next_occurrence_at=? where id=?",
                Timestamp.from(nextOccurrence), reminderId);
    }

    public int maxAttempts() { return maxAttempts; }

    public String channel() { return provider.channel(); }
}
