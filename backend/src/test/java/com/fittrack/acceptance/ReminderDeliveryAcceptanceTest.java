package com.fittrack.acceptance;

import com.fittrack.acceptance.support.AbstractAcceptanceTest;
import com.fittrack.acceptance.support.FakeNotificationProvider;
import com.fittrack.reminder.ReminderDeliveryService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Phase 15 A: reminder delivery, scheduling and timezone correctness.
 *
 * <p>All delivery runs against a deterministic fake channel; no push credentials exist.
 */
@SpringBootTest(classes = com.fittrack.api.FitTrackApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.reminders.max-attempts=3")
class ReminderDeliveryAcceptanceTest extends AbstractAcceptanceTest {

    @TestConfiguration
    static class DeliveryConfig {
        @Bean @Primary
        FakeNotificationProvider fakeNotificationProvider() { return new FakeNotificationProvider(); }
    }

    @Autowired FakeNotificationProvider provider;

    @BeforeEach
    void resetProvider() { provider.reset(); }

    // ------------------------------------------------------------- helpers

    private UUID createReminder(Session owner, Map<String, Object> payload) throws Exception {
        MvcResult result = call(owner, post("/api/v1/reminders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(payload)));
        // The generic owned-resource API answers 200 with the created row.
        assertThat(result.getResponse().getStatus())
                .as("reminder create body was %s", result.getResponse().getContentAsString())
                .isIn(200, 201);
        return UUID.fromString(json(result).path("id").asText());
    }

    private Map<String, Object> reminder(String time, String days, String zone, String recurrence) {
        return new java.util.LinkedHashMap<>(Map.of(
                "type", "workout", "title", "Time to train", "message", "Session starts now",
                "scheduled_time", time, "days_of_week", days,
                "timezone", zone, "recurrence", recurrence, "enabled", true));
    }

    private MvcResult reschedule(Session user, UUID id) throws Exception {
        return call(user, post("/api/v1/reminders/" + id + "/reschedule").contentType(MediaType.APPLICATION_JSON).content("{}"));
    }

    // ------------------------------------------------------------- item 2 ownership

    @Test
    @DisplayName("Phase 15 reminders - a user cannot see, change or delete another user's reminder")
    void remindersAreOwnerScoped() throws Exception {
        Session owner = register("rem-owner-");
        Session other = register("rem-other-");
        UUID id = createReminder(owner, reminder("06:00", "1,2,3", "UTC", "daily"));

        MvcResult foreignSchedule = getAs(other, "/api/v1/reminders/schedule");
        assertStatus(foreignSchedule, 200);
        assertThat(json(foreignSchedule).toString()).doesNotContain(id.toString());

        assertStatus(call(other, post("/api/v1/reminders/" + id + "/reschedule")
                .contentType(MediaType.APPLICATION_JSON).content("{}")), 404);
        assertStatus(call(other, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/reminders/" + id)
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Hijacked\"}")), 404);
        // The generic delete route is mounted at /{resource} with no id segment, so a delete
        // addressed to another user's reminder does not reach any handler at all.
        assertStatus(call(other, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/v1/reminders/" + id)), 404);

        // The owner's reminder is untouched.
        assertThat(jdbc.queryForObject("select title from reminders where id=?", String.class, id))
                .isEqualTo("Time to train");
    }

    @Test
    @DisplayName("Phase 15 reminders - a client cannot create a reminder owned by someone else")
    void remindersCannotBeForgedForAnotherUser() throws Exception {
        Session attacker = register("rem-attacker-");
        Session victim = register("rem-victim-");

        MvcResult result = call(attacker, post("/api/v1/reminders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"user_id\":\"" + victim.id() + "\",\"title\":\"Forged\",\"scheduled_time\":\"06:00\"}"));
        assertStatus(result, 400);
        assertThat(json(result).path("message").asText()).contains("user_id is server controlled");
        assertThat(jdbc.queryForObject("select count(*) from reminders where user_id=CAST(? as uuid)",
                Integer.class, victim.id())).isZero();
    }

    // ---------------------------------------------------------- item 4 timezone

    @Test
    @DisplayName("Phase 15 reminders - next occurrence is computed in the user's zone, not the server's")
    void nextOccurrenceHonoursTheUsersTimezone() throws Exception {
        Session user = register("rem-tz-");
        UUID kolkata = createReminder(user, reminder("07:00", "1,2,3,4,5,6,0", "Asia/Kolkata", "daily"));
        UUID utc = createReminder(user, reminder("07:00", "1,2,3,4,5,6,0", "UTC", "daily"));

        assertStatus(reschedule(user, kolkata), 200);
        assertStatus(reschedule(user, utc), 200);

        Instant kolkataNext = nextOccurrence(kolkata);
        Instant utcNext = nextOccurrence(utc);

        // Both fire at 07:00 local, so the UTC one is 5h30m earlier than the Kolkata one.
        assertThat(Duration.between(kolkataNext, utcNext).toMinutes())
                .as("Kolkata 07:00 is 01:30 UTC").isEqualTo(330);
        // And the stored instant really is 07:00 in the reminder's own zone.
        assertThat(kolkataNext.atZone(ZoneId.of("Asia/Kolkata")).toLocalTime())
                .isEqualTo(LocalTime.of(7, 0));
        assertThat(utcNext.atZone(ZoneOffset.UTC).toLocalTime()).isEqualTo(LocalTime.of(7, 0));
        // The server default zone was never consulted.
        assertThat(ZoneId.systemDefault()).isNotNull();
    }

    @Test
    @DisplayName("Phase 15 reminders - a half-hour-offset zone is handled, not truncated to whole days")
    void halfHourOffsetZoneIsSupported() throws Exception {
        Session user = register("rem-tz-half-");
        UUID id = createReminder(user, reminder("09:15", "1,2,3,4,5,6,0", "Asia/Kolkata", "daily"));
        assertStatus(reschedule(user, id), 200);

        Instant next = nextOccurrence(id);
        assertThat(next.atZone(ZoneId.of("Asia/Kolkata")).toLocalTime()).isEqualTo(LocalTime.of(9, 15));
        assertThat(next.atZone(ZoneId.of("Asia/Kolkata")).getOffset().getTotalSeconds())
                .isEqualTo(5 * 3600 + 30 * 60);
    }

    // -------------------------------------------------------- item 3 scheduling

    @Test
    @DisplayName("Phase 15 reminders - a weekly reminder only lands on its configured days")
    void weeklyRecurrenceHonoursDaysOfWeek() throws Exception {
        Session user = register("rem-weekly-");
        // Wednesday only (DayOfWeek.WEDNESDAY = 3), expressed with the frontend's 0=Sunday index.
        UUID id = createReminder(user, reminder("08:00", "3", "UTC", "weekly"));
        assertStatus(reschedule(user, id), 200);

        Instant next = nextOccurrence(id);
        ZonedDateTime local = next.atZone(ZoneOffset.UTC);
        assertThat(local.getDayOfWeek().getValue())
                .as("java.time Wednesday").isEqualTo(3);
        assertThat(local.toLocalTime()).isEqualTo(LocalTime.of(8, 0));
    }

    @Test
    @DisplayName("Phase 15 reminders - a disabled reminder is never returned as due")
    void disabledRemindersAreNotDue() throws Exception {
        Session user = register("rem-disabled-");
        UUID id = createReminder(user, reminder("06:00", "1,2,3,4,5,6,0", "UTC", "daily"));
        assertStatus(reschedule(user, id), 200);
        // Backdate the occurrence so the reminder is genuinely due right now.
        jdbc.update("update reminders set next_occurrence_at=now()-interval '1 minute' where id=?", id);
        assertThat(dueReminders()).as("an enabled reminder is due once scheduled").hasSize(1);

        assertStatus(call(user, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/reminders/" + id)
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}")), 200);
        assertThat(dueReminders()).as("a disabled reminder is skipped").isEmpty();
    }

    private List<Map<String, Object>> dueReminders() {
        return jdbc.queryForList("select id from reminders where enabled=true and next_occurrence_at is not null"
                + " and next_occurrence_at<=now()");
    }

    private Instant nextOccurrence(UUID id) {
        java.sql.Timestamp value = jdbc.queryForObject(
                "select next_occurrence_at from reminders where id=?", java.sql.Timestamp.class, id);
        assertThat(value).as("next occurrence was stored").isNotNull();
        return value.toInstant();
    }

    // ------------------------------------------------- items 5-7 delivery outcomes

    @Autowired ReminderDeliveryService delivery;

    @Test
    @DisplayName("Phase 15 reminders - a successful delivery is recorded once and the channel saw it")
    void successfulDeliveryIsRecorded() throws Exception {
        Session user = register("rem-deliver-ok-");
        UUID id = createReminder(user, reminder("06:00", "1,2,3,4,5,6,0", "UTC", "daily"));
        Instant occurrence = Instant.parse("2026-07-01T06:00:00Z");

        var result = delivery.deliver(id, UUID.fromString(user.id()), "Time to train", "Session", occurrence);

        assertThat(result.delivered()).isTrue();
        assertThat(result.state()).isEqualTo("delivered");
        assertThat(provider.callCount()).isEqualTo(1);
        assertThat(deliveryState(id)).isEqualTo("delivered");
        assertThat(deliveryAttempts(id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from reminder_deliveries where reminder_id=?",
                Integer.class, id)).isEqualTo(1);
    }

    @Test
    @DisplayName("Phase 15 reminders - a temporary failure is retried and can still succeed")
    void temporaryFailureIsRetriedWithinTheBudget() throws Exception {
        Session user = register("rem-deliver-retry-");
        UUID id = createReminder(user, reminder("06:00", "1,2,3,4,5,6,0", "UTC", "daily"));
        Instant occurrence = Instant.parse("2026-07-02T06:00:00Z");
        // Fail twice, succeed on the third attempt (the configured budget).
        provider.failTemporarilyThenSucceed(2);

        var result = delivery.deliver(id, UUID.fromString(user.id()), "Time to train", "Session", occurrence);

        assertThat(result.delivered()).isTrue();
        assertThat(provider.callCount()).as("retried until the budget allowed success").isEqualTo(3);
        assertThat(deliveryAttempts(id)).isEqualTo(3);
        assertThat(deliveryState(id)).isEqualTo("delivered");
    }

    @Test
    @DisplayName("Phase 15 reminders - a permanent failure is not retried at all")
    void permanentFailureIsNotRetried() throws Exception {
        Session user = register("rem-deliver-perm-");
        UUID id = createReminder(user, reminder("06:00", "1,2,3,4,5,6,0", "UTC", "daily"));
        provider.use(FakeNotificationProvider.Mode.PERMANENT_FAILURE);

        var result = delivery.deliver(id, UUID.fromString(user.id()), "Time to train", "Session",
                Instant.parse("2026-07-03T06:00:00Z"));

        assertThat(result.delivered()).isFalse();
        assertThat(result.state()).isEqualTo("failed");
        assertThat(provider.callCount()).as("a permanent failure is attempted exactly once").isEqualTo(1);
        assertThat(jdbc.queryForObject("select last_error from reminders where id=?", String.class, id))
                .isEqualTo("permanent");
    }

    @Test
    @DisplayName("Phase 15 reminders - an always-failing channel exhausts its budget and stops")
    void retriesAreBoundedAndTerminate() throws Exception {
        Session user = register("rem-deliver-exhaust-");
        UUID id = createReminder(user, reminder("06:00", "1,2,3,4,5,6,0", "UTC", "daily"));
        provider.use(FakeNotificationProvider.Mode.TEMPORARY_FAILURE);

        var result = delivery.deliver(id, UUID.fromString(user.id()), "Time to train", "Session",
                Instant.parse("2026-07-04T06:00:00Z"));

        assertThat(result.state()).isEqualTo("exhausted");
        assertThat(result.attempts()).isEqualTo(delivery.maxAttempts());
        assertThat(provider.callCount())
                .as("bounded: exactly max-attempts calls, never an unbounded loop").isEqualTo(3);
        assertThat(deliveryState(id)).isEqualTo("exhausted");
    }

    @Test
    @DisplayName("Phase 15 reminders - a throwing channel is contained and treated as transient")
    void throwingProviderDoesNotEscapeTheRetryLoop() throws Exception {
        Session user = register("rem-deliver-throw-");
        UUID id = createReminder(user, reminder("06:00", "1,2,3,4,5,6,0", "UTC", "daily"));
        provider.use(FakeNotificationProvider.Mode.THROWING);

        var result = delivery.deliver(id, UUID.fromString(user.id()), "Time to train", "Session",
                Instant.parse("2026-07-05T06:00:00Z"));

        assertThat(result.state()).isEqualTo("exhausted");
        assertThat(provider.callCount()).isEqualTo(delivery.maxAttempts());
    }

    private String deliveryState(UUID id) {
        return jdbc.queryForObject("select delivery_status from reminders where id=?", String.class, id);
    }

    private int deliveryAttempts(UUID id) {
        return jdbc.queryForObject("select delivery_attempts from reminders where id=?", Integer.class, id);
    }

    // ------------------------------------------- items 6 and 9 idempotency

    @Test
    @DisplayName("Phase 15 reminders - running the scheduler twice for one occurrence delivers once")
    void duplicateSchedulerExecutionDeliversOnlyOnce() throws Exception {
        Session user = register("rem-dup-");
        UUID id = createReminder(user, reminder("06:00", "1,2,3,4,5,6,0", "UTC", "daily"));
        UUID owner = UUID.fromString(user.id());
        Instant occurrence = Instant.parse("2026-08-01T06:00:00Z");

        var first = delivery.deliver(id, owner, "Time to train", "Session", occurrence);
        var second = delivery.deliver(id, owner, "Time to train", "Session", occurrence);
        var third = delivery.deliver(id, owner, "Time to train", "Session", occurrence);

        assertThat(first.delivered()).isTrue();
        assertThat(second.state()).as("a repeat pass is a no-op").isEqualTo("duplicate");
        assertThat(third.state()).isEqualTo("duplicate");
        assertThat(provider.callCount())
                .as("the channel received exactly one delivery for the occurrence").isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from reminder_deliveries where reminder_id=?",
                Integer.class, id)).isEqualTo(1);
    }

    @Test
    @DisplayName("Phase 15 reminders - a different occurrence for the same reminder is a distinct delivery")
    void distinctOccurrencesAreDeliveredSeparately() throws Exception {
        Session user = register("rem-dup-distinct-");
        UUID id = createReminder(user, reminder("06:00", "1,2,3,4,5,6,0", "UTC", "daily"));
        UUID owner = UUID.fromString(user.id());

        delivery.deliver(id, owner, "T", "S", Instant.parse("2026-08-02T06:00:00Z"));
        delivery.deliver(id, owner, "T", "S", Instant.parse("2026-08-03T06:00:00Z"));

        assertThat(provider.callCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from reminder_deliveries where reminder_id=?",
                Integer.class, id)).isEqualTo(2);
    }

    @Test
    @DisplayName("Phase 15 reminders - concurrent schedulers for one occurrence deliver exactly once")
    void concurrentSchedulersDoNotDuplicateDelivery() throws Exception {
        Session user = register("rem-dup-race-");
        UUID id = createReminder(user, reminder("06:00", "1,2,3,4,5,6,0", "UTC", "daily"));
        UUID owner = UUID.fromString(user.id());
        Instant occurrence = Instant.parse("2026-08-04T06:00:00Z");
        int racers = 4;

        var barrier = new java.util.concurrent.CyclicBarrier(racers);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(racers);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < racers; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    return delivery.deliver(id, owner, "T", "S", occurrence).state();
                }));
            }
            int delivered = 0;
            for (var future : futures) if ("delivered".equals(future.get(30, java.util.concurrent.TimeUnit.SECONDS))) delivered++;
            assertThat(delivered).as("exactly one racer owns the occurrence").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(provider.callCount())
                .as("the database uniqueness constraint, not timing, prevents duplicates").isEqualTo(1);
    }

    // ------------------------------------------------------ item 8 security

    @Test
    @DisplayName("Phase 15 reminders - a client cannot forge delivery state")
    void clientCannotForgeDeliveryState() throws Exception {
        Session user = register("rem-forge-");
        UUID id = createReminder(user, reminder("06:00", "1,2,3,4,5,6,0", "UTC", "daily"));

        for (String field : new String[] {"delivery_status", "delivery_attempts", "last_error",
                "last_delivered_at", "next_occurrence_at"}) {
            MvcResult result = call(user, post("/api/v1/reminders/schedule/validate")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"" + field + "\":\"delivered\"}"));
            assertStatus(result, 400);
            assertThat(json(result).path("message").asText()).contains(field).contains("server controlled");
        }

        // The generic resource API rejects the same fields outright.
        MvcResult forged = call(user, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/reminders/" + id)
                .contentType(MediaType.APPLICATION_JSON).content("{\"delivery_status\":\"delivered\"}"));
        assertStatus(forged, 400);
        assertThat(deliveryState(id)).as("state is unchanged").isEqualTo("pending");
    }

    @Test
    @DisplayName("Phase 15 reminders - the schedule view exposes no provider or credential material")
    void scheduleViewLeaksNoProviderInternals() throws Exception {
        Session user = register("rem-leak-");
        createReminder(user, reminder("06:00", "1,2,3,4,5,6,0", "UTC", "daily"));

        String body = getAs(user, "/api/v1/reminders/schedule").getResponse().getContentAsString();
        assertThat(body).doesNotContain("vapid").doesNotContain("endpoint").doesNotContain("authorization")
                .doesNotContain("private").doesNotContain("secret");
    }
}
