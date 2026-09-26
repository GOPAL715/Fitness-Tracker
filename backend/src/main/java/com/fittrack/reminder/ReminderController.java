package com.fittrack.reminder;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Reminder scheduling surface.
 *
 * <p>Reminders themselves are still created through the generic owned-resource API, which enforces
 * JWT ownership. This controller adds the scheduling view and the server-controlled next-occurrence
 * computation. Delivery state is never writable from the client: {@code delivery_status},
 * {@code delivery_attempts}, {@code last_error} and {@code last_delivered_at} are rejected as inputs.
 */
@RestController
@RequestMapping("/api/v1/reminders")
public class ReminderController {

    /** Fields a client may never set; they are server-controlled. */
    private static final List<String> SERVER_CONTROLLED =
            List.of("user_id", "id", "delivery_status", "delivery_attempts", "last_error",
                    "last_delivered_at", "next_occurrence_at");

    private final JdbcTemplate jdbc;

    public ReminderController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/schedule")
    public List<Map<String, Object>> schedule(@AuthenticationPrincipal String user) {
        uuid(user);
        return jdbc.queryForList("select id,type,title,message,scheduled_time,days_of_week,timezone,"
                        + "recurrence,enabled,delivery_status,delivery_attempts,last_error,"
                        + "last_delivered_at,next_occurrence_at from reminders"
                        + " where user_id=CAST(? AS uuid) order by next_occurrence_at nulls last",
                user);
    }

    /**
     * Recomputes and stores the next occurrence for one reminder owned by the caller.
     *
     * <p>The stored instant is derived in the reminder's own zone, never the server's.
     */
    @PostMapping("/{id}/reschedule")
    public Map<String, Object> reschedule(@PathVariable UUID id, @AuthenticationPrincipal String user) {
        UUID owner = uuid(user);
        Map<String, Object> reminder = owned(id, owner);
        Instant next = ReminderSchedule.nextOccurrence(
                LocalTime.parse(String.valueOf(reminder.get("scheduled_time"))),
                String.valueOf(reminder.get("days_of_week")),
                ReminderSchedule.Recurrence.parse(String.valueOf(reminder.get("recurrence"))),
                String.valueOf(reminder.get("timezone")),
                Instant.now()).orElseThrow(() -> new NoSuchElementException("No future occurrence"));

        jdbc.update("update reminders set next_occurrence_at=? where id=? and user_id=CAST(? AS uuid)",
                Timestamp.from(next), id, owner);
        return Map.of("id", id, "next_occurrence_at", next, "timezone", reminder.get("timezone"));
    }

    /**
     * Rejects attempts to write server-controlled delivery state.
     *
     * <p>Mapped under {@code /schedule} so the generic owned-resource route cannot shadow it.
     */
    @PostMapping("/schedule/validate")
    public Map<String, Object> validate(@RequestBody Map<String, Object> body) {
        for (String field : SERVER_CONTROLLED) {
            if (body.containsKey(field)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        field + " is server controlled");
            }
        }
        if (body.containsKey("timezone")) ReminderSchedule.zone(String.valueOf(body.get("timezone")));
        return Map.of("ok", true);
    }

    private Map<String, Object> owned(UUID id, UUID owner) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id,type,title,message,scheduled_time,days_of_week,timezone,recurrence,enabled"
                        + " from reminders where id=? and user_id=CAST(? AS uuid)", id, owner);
        if (rows.isEmpty()) throw new NoSuchElementException("Reminder not found");
        return rows.get(0);
    }

    private static UUID uuid(String principal) {
        try {
            return UUID.fromString(principal);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }
}
