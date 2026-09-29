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
        // Phase 17: the provider names the cause while it still knows it. Recorded on the occurrence
        // row only - the coarse lastError above is unchanged, so anything already reading it keeps
        // working exactly as before.
        FailureCategory category = FailureCategory.UNKNOWN;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            attempts = attempt;
            Outcome outcome;
            try {
                outcome = provider.deliver(
                        new NotificationDeliveryProvider.DeliveryRequest(reminderId, userId, title, message, occurrence));
                category = provider.lastFailureCategory();
            } catch (RuntimeException e) {
                // An exploding provider is treated as transient; it must not escape the loop.
                outcome = Outcome.TEMPORARY_FAILURE;
                lastError = "provider_error";
                // A provider that threw never got far enough to establish a cause, and the message
                // is not a reliable signal, so this stays UNKNOWN rather than being read for a keyword.
                category = FailureCategory.UNKNOWN;
            }

            if (outcome == Outcome.DELIVERED) {
                finish(reminderId, occurrence, "delivered", attempts, null, null);
                return new DeliveryResult(reminderId, "delivered", attempts, true);
            }
            if (outcome == Outcome.PERMANENT_FAILURE) {
                // Permanent failures are never retried.
                finish(reminderId, occurrence, "failed", attempts, "permanent", category);
                return new DeliveryResult(reminderId, "failed", attempts, false);
            }
        }

        // Retry budget exhausted: the occurrence is closed, not retried forever. The category is the
        // one from the final attempt, which is the attempt that exhausted the budget.
        finish(reminderId, occurrence, "exhausted", attempts, lastError, category);
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

    /**
     * Closes the occurrence and mirrors its state onto the reminder.
     *
     * <p>{@code failureCategory} is the one Phase 17 addition, and it is written here only - the same
     * place, the same transaction and the same states as before. Passing null leaves the column NULL,
     * which is what a successful delivery stores: a delivery that worked has no failure to categorise.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void finish(UUID reminderId, Instant occurrence, String state, int attempts, String lastError,
                FailureCategory failureCategory) {
        jdbc.update("update reminder_deliveries set state=?,attempts=?,last_error=?,delivered_at=?,"
                        + " failure_category=? where reminder_id=? and occurrence_at=?",
                state, attempts, lastError,
                "delivered".equals(state) ? Timestamp.from(Instant.now()) : null,
                failureCategory == null ? null : failureCategory.name(),
                reminderId, Timestamp.from(occurrence));
        // The reminder's own summary columns are deliberately left alone. They describe the most
        // recent occurrence, and adding a category there would silently change the shape of an
        // existing API response for a value Phase 17 exposes through the history endpoint instead.
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

    /**
     * One occurrence, as the history API returns it.
     *
     * <p>{@code failureCategory} is a closed vocabulary of names, never a provider message. A null
     * value is reported as {@link FailureCategory#UNKNOWN} rather than omitted, so a client always
     * receives a name it can render and never has to distinguish "no cause" from "old record".
     */
    public record DeliveryAttempt(Instant occurrenceAt, String state, int attempts, String reason,
                                  FailureCategory failureCategory, Instant deliveredAt, Instant recordedAt) {}

    /** One page of a reminder's delivery history. */
    public record DeliveryHistory(List<DeliveryAttempt> attempts, int limit, int offset, boolean hasMore) {}

    /**
     * The caller's delivery history for one reminder, newest first.
     *
     * <p>Scoped to the owner in the query itself rather than by filtering afterwards, so another
     * user's occurrences can never be read even for a single page.
     *
     * <p>Reads {@code reminder_deliveries}, the ledger Phase 12 already writes on every delivery. No
     * second table is involved, and this method performs no writes.
     *
     * <p>Pagination is offset-based and bounded. The unique key on
     * {@code (reminder_id, occurrence_at)} already serves this as an index, and {@code occurrence_at}
     * is unique within a reminder, so the ordering is total and no row can be skipped or repeated
     * between pages. The limit is clamped rather than trusted, so an unbounded read cannot be asked
     * for.
     *
     * <p>One extra row is fetched to decide {@code hasMore} without a second count query.
     */
    public DeliveryHistory history(UUID reminderId, UUID owner, int limit, int offset) {
        int boundedLimit = Math.min(Math.max(limit, 1), 100);
        int boundedOffset = Math.max(offset, 0);
        List<DeliveryAttempt> rows = jdbc.query(
                "select occurrence_at,state,attempts,last_error,failure_category,delivered_at,created_at"
                        + " from reminder_deliveries where reminder_id=? and user_id=CAST(? AS uuid)"
                        + " order by occurrence_at desc limit ? offset ?",
                (rs, i) -> new DeliveryAttempt(
                        rs.getTimestamp("occurrence_at").toInstant(),
                        rs.getString("state"),
                        rs.getInt("attempts"),
                        rs.getString("last_error"),
                        categoryOf(rs.getString("failure_category")),
                        rs.getTimestamp("delivered_at") == null
                                ? null : rs.getTimestamp("delivered_at").toInstant(),
                        rs.getTimestamp("created_at") == null
                                ? null : rs.getTimestamp("created_at").toInstant()),
                reminderId, owner, boundedLimit + 1, boundedOffset);

        boolean hasMore = rows.size() > boundedLimit;
        List<DeliveryAttempt> page = hasMore ? rows.subList(0, boundedLimit) : rows;
        return new DeliveryHistory(List.copyOf(page), boundedLimit, boundedOffset, hasMore);
    }

    /**
     * Maps a stored category name back to the vocabulary, defensively.
     *
     * <p>An unrecognised or absent name is UNKNOWN. That covers both records written before the
     * column existed and any value this build does not recognise, so a row can never fail to render
     * and no cause is ever invented for it.
     */
    private static FailureCategory categoryOf(String stored) {
        if (stored == null || stored.isBlank()) return FailureCategory.UNKNOWN;
        try {
            return FailureCategory.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return FailureCategory.UNKNOWN;
        }
    }

    public int maxAttempts() { return maxAttempts; }

    public String channel() { return provider.channel(); }
}
