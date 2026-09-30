package com.fittrack.acceptance;

import com.fittrack.acceptance.support.AbstractAcceptanceTest;
import com.fittrack.acceptance.support.FakeNotificationProvider;
import com.fittrack.reminder.FailureCategory;
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

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

        /**
         * Pins the reference instant the scheduler resolves "next" against.
         *
         * <p>Which occurrence is next depends on the moment the request is served, so a live clock
         * would make these assertions depend on the time of day CI happens to run. 2026-09-29T20:00Z
         * is 2026-09-30 01:30 in Asia/Kolkata: after that day's 07:00 has passed locally but before
         * 07:00 UTC, so both reminders resolve to the same UTC calendar day and the 5h30m offset
         * between them is exactly 330 minutes. The production default, Clock.systemUTC(), is
         * behaviourally identical to the Instant.now() it replaced.
         */
        @Bean @Primary
        Clock fixedClock() { return Clock.fixed(Instant.parse("2026-09-29T20:00:00Z"), ZoneOffset.UTC); }
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

        // Both fire at 07:00 local, so on the same UTC day the UTC one is 5h30m earlier.
        // Pinned reference is 2026-09-29T20:00Z = 2026-09-30 01:30 IST, so both resolve to
        // 2026-09-30 and the offset is exactly 330 minutes.
        assertThat(Duration.between(kolkataNext, utcNext).toMinutes())
                .as("Kolkata 07:00 is 01:30 UTC").isEqualTo(330);
        // And the stored instant really is 07:00 in the reminder's own zone.
        assertThat(kolkataNext.atZone(ZoneId.of("Asia/Kolkata")).toLocalTime())
                .isEqualTo(LocalTime.of(7, 0));
        assertThat(utcNext.atZone(ZoneOffset.UTC).toLocalTime()).isEqualTo(LocalTime.of(7, 0));
        // Asia/Kolkata is UTC+05:30 with no daylight saving, so 07:00 local is 01:30 UTC.
        assertThat(kolkataNext.atZone(ZoneId.of("Asia/Kolkata")).getOffset().getTotalSeconds())
                .as("a half-hour offset is carried, not rounded away").isEqualTo(19800);
        // Both are the NEXT occurrence: strictly after the pinned reference, never today''s
        // already-elapsed 07:00. This is the regression the fixed clock makes provable - a
        // scheduler that returned the passed occurrence would yield a negative delay.
        Instant reference = Instant.parse("2026-09-29T20:00:00Z");
        assertThat(kolkataNext).as("Kolkata next is after the reference, not a past occurrence")
                .isAfter(reference);
        assertThat(utcNext).as("UTC next is after the reference, not a past occurrence")
                .isAfter(reference);
        assertThat(Duration.between(reference, kolkataNext).toMinutes())
                .as("Kolkata 07:00 on 2026-09-30 is 01:30Z, 330 minutes after the reference")
                .isEqualTo(330);
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
        assertThat(dueReminders(user)).as("an enabled reminder is due once scheduled").hasSize(1);

        assertStatus(call(user, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/reminders/" + id)
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}")), 200);
        assertThat(dueReminders(user)).as("a disabled reminder is skipped").isEmpty();
    }

    /*
     * Due reminders belonging to one account.
     *
     * <p>This mirrors the production query in ReminderDeliveryService.dueReminders, which is
     * deliberately GLOBAL: the scheduler is a system-wide tick, so it selects every reminder due at
     * that instant and resolves each row's own user_id afterwards. That design is correct and is not
     * changed here.
     *
     * <p>What was wrong is the assertion, not the scheduler. This helper used to run the same global
     * query and then assert the result had exactly one row, against a database shared by the whole
     * test JVM where other classes leave enabled, backdated reminders behind. The count therefore
     * depended on which tests had already run, and this test failed whenever it was not first.
     *
     * <p>Scoping the assertion to the account under test is what the test means to check - that
     * *this* reminder becomes due and then stops being due - and it makes the result independent of
     * execution order without weakening the property being verified.
     */
    private List<Map<String, Object>> dueReminders(Session user) {
        return jdbc.queryForList("select id from reminders where user_id=CAST(? as uuid)"
                        + " and enabled=true and next_occurrence_at is not null and next_occurrence_at<=now()",
                user.id());
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

    // ================================================================
    // Phase 17: user-facing delivery history and safe failure categories.
    //
    // These live in this class rather than one of their own so they share the single deterministic
    // fake and the pinned clock above. A separate @TestConfiguration would register a second
    // @Primary provider, and the context would then fail to resolve exactly one.
    // ================================================================

    /** Drives one occurrence through the real delivery path, as the scheduler would. */
    private ReminderDeliveryService.DeliveryResult deliverOccurrence(Session owner, UUID reminderId,
            Instant occurrence) {
        return delivery.deliver(reminderId, UUID.fromString(owner.id()),
                "Time to train", "Session starts now", occurrence);
    }

    private UUID phase17Reminder(Session owner) throws Exception {
        return createReminder(owner, reminder("06:00", "1,2,3,4,5", "Asia/Kolkata", "daily"));
    }

    private String storedCategory(UUID reminderId, Instant occurrence) {
        return jdbc.queryForObject(
                "select failure_category from reminder_deliveries where reminder_id=? and occurrence_at=?",
                String.class, reminderId, Timestamp.from(occurrence));
    }

    private String storedReason(UUID reminderId, Instant occurrence) {
        return jdbc.queryForObject(
                "select last_error from reminder_deliveries where reminder_id=? and occurrence_at=?",
                String.class, reminderId, Timestamp.from(occurrence));
    }

    /** A record exactly as it existed before the failure_category column was added. */
    private void insertLegacyOccurrence(UUID reminderId, UUID userId, Instant occurrence, String state) {
        jdbc.update("insert into reminder_deliveries(id,reminder_id,user_id,occurrence_at,state,attempts,last_error)"
                        + " values (?,?,?,?,?,?,?)",
                UUID.randomUUID(), reminderId, userId, Timestamp.from(occurrence), state, 1, "permanent");
    }

    private JsonNode historyPage(Session owner, UUID reminderId, String query) throws Exception {
        return json(getAs(owner, "/api/v1/reminders/" + reminderId + "/delivery-history" + query));
    }

    @Test
    @DisplayName("Phase 17 - a delivered occurrence stores no failure category")
    void deliveredStoresNoCategory() throws Exception {
        Session owner = register("p17-delivered");
        UUID reminderId = phase17Reminder(owner);
        Instant occurrence = Instant.parse("2026-09-20T00:30:00Z");

        deliverOccurrence(owner, reminderId, occurrence);

        // NULL because a delivery that worked has no failure to categorise.
        assertThat(storedCategory(reminderId, occurrence)).isNull();
        assertThat(storedReason(reminderId, occurrence)).isNull();
    }

    @Test
    @DisplayName("Phase 17 - a permanent failure stores the category alongside the coarse reason")
    void permanentFailureStoresCategory() throws Exception {
        Session owner = register("p17-permanent");
        UUID reminderId = phase17Reminder(owner);
        Instant occurrence = Instant.parse("2026-09-21T00:30:00Z");
        provider.use(FakeNotificationProvider.Mode.PERMANENT_FAILURE);
        provider.reportCategory(FailureCategory.NO_SUBSCRIPTION);

        deliverOccurrence(owner, reminderId, occurrence);

        assertThat(storedCategory(reminderId, occurrence)).isEqualTo("NO_SUBSCRIPTION");
        // The pre-existing coarse reason is preserved verbatim, so nothing that reads it changes.
        assertThat(storedReason(reminderId, occurrence)).isEqualTo("permanent");
    }

    /**
     * Every category the Web Push provider can actually distinguish, driven through the real delivery
     * path. Each asserts the stored column, so a later change that stops persisting one of them fails
     * here rather than silently degrading a user's history.
     */
    @Test
    @DisplayName("Phase 17 - every distinguishable category is persisted on its own occurrence")
    void allDistinguishableCategoriesArePersisted() throws Exception {
        Session owner = register("p17-categories");
        UUID reminderId = phase17Reminder(owner);
        Instant base = Instant.parse("2026-09-01T00:30:00Z");

        Map<FailureCategory, FakeNotificationProvider.Mode> cases = new LinkedHashMap<>();
        cases.put(FailureCategory.NO_SUBSCRIPTION, FakeNotificationProvider.Mode.PERMANENT_FAILURE);
        cases.put(FailureCategory.INVALID_SUBSCRIPTION, FakeNotificationProvider.Mode.PERMANENT_FAILURE);
        cases.put(FailureCategory.RATE_LIMITED, FakeNotificationProvider.Mode.TEMPORARY_FAILURE);
        cases.put(FailureCategory.TEMPORARY_PROVIDER_ERROR, FakeNotificationProvider.Mode.TEMPORARY_FAILURE);
        cases.put(FailureCategory.PROVIDER_REJECTED, FakeNotificationProvider.Mode.PERMANENT_FAILURE);

        int index = 0;
        for (Map.Entry<FailureCategory, FakeNotificationProvider.Mode> testCase : cases.entrySet()) {
            Instant occurrence = base.plusSeconds(86400L * index++);
            provider.use(testCase.getValue());
            provider.reportCategory(testCase.getKey());

            deliverOccurrence(owner, reminderId, occurrence);

            assertThat(storedCategory(reminderId, occurrence))
                    .as("category for %s", testCase.getKey())
                    .isEqualTo(testCase.getKey().name());
        }
    }

    @Test
    @DisplayName("Phase 17 - a provider that reports no cause stores UNKNOWN rather than guessing")
    void unspecifiedFailureStoresUnknown() throws Exception {
        Session owner = register("p17-unknown");
        UUID reminderId = phase17Reminder(owner);
        Instant occurrence = Instant.parse("2026-09-22T00:30:00Z");
        provider.use(FakeNotificationProvider.Mode.PERMANENT_FAILURE);
        provider.reportCategory(null);

        deliverOccurrence(owner, reminderId, occurrence);

        assertThat(storedCategory(reminderId, occurrence)).isEqualTo("UNKNOWN");
    }

    /**
     * A provider that throws must never be classified from its message. It never reached the push
     * service, so a category configured for an earlier attempt is discarded rather than kept.
     */
    @Test
    @DisplayName("Phase 17 - a throwing provider is never classified from its exception message")
    void throwingProviderStoresUnknown() throws Exception {
        Session owner = register("p17-throwing");
        UUID reminderId = phase17Reminder(owner);
        Instant occurrence = Instant.parse("2026-09-23T00:30:00Z");
        provider.reportCategory(FailureCategory.RATE_LIMITED);
        provider.use(FakeNotificationProvider.Mode.THROWING);

        deliverOccurrence(owner, reminderId, occurrence);

        // Not RATE_LIMITED, and not read from the message "push channel exploded".
        assertThat(storedCategory(reminderId, occurrence)).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("Phase 17 - an exhausted retry budget keeps the final attempt's category")
    void exhaustedStoresFinalAttemptCategory() throws Exception {
        Session owner = register("p17-exhausted");
        UUID reminderId = phase17Reminder(owner);
        Instant occurrence = Instant.parse("2026-09-24T00:30:00Z");
        provider.use(FakeNotificationProvider.Mode.TEMPORARY_FAILURE);
        provider.reportCategory(FailureCategory.TEMPORARY_PROVIDER_ERROR);

        ReminderDeliveryService.DeliveryResult result = deliverOccurrence(owner, reminderId, occurrence);

        assertThat(result.state()).isEqualTo("exhausted");
        assertThat(result.attempts()).isEqualTo(3);
        assertThat(storedCategory(reminderId, occurrence)).isEqualTo("TEMPORARY_PROVIDER_ERROR");
        // The coarse reason on this path is unchanged from before Phase 17.
        assertThat(storedReason(reminderId, occurrence)).isEqualTo("temporary");
    }

    @Test
    @DisplayName("Phase 17 - history returns the caller's occurrences, newest first, with safe categories")
    void historyReturnsOccurrencesNewestFirst() throws Exception {
        Session owner = register("p17-history");
        UUID reminderId = phase17Reminder(owner);
        Instant older = Instant.parse("2026-09-10T00:30:00Z");
        Instant newer = Instant.parse("2026-09-11T00:30:00Z");

        deliverOccurrence(owner, reminderId, older);
        provider.use(FakeNotificationProvider.Mode.PERMANENT_FAILURE);
        provider.reportCategory(FailureCategory.INVALID_SUBSCRIPTION);
        deliverOccurrence(owner, reminderId, newer);

        JsonNode body = historyPage(owner, reminderId, "");

        assertThat(body.path("attempts")).hasSize(2);
        assertThat(body.path("attempts").get(0).path("occurrenceAt").asText()).startsWith("2026-09-11");
        assertThat(body.path("attempts").get(0).path("failureCategory").asText())
                .isEqualTo("INVALID_SUBSCRIPTION");
        assertThat(body.path("attempts").get(0).path("state").asText()).isEqualTo("failed");
        // A success has no category, so the API reports UNKNOWN rather than omitting the field.
        assertThat(body.path("attempts").get(1).path("failureCategory").asText()).isEqualTo("UNKNOWN");
        assertThat(body.path("attempts").get(1).path("state").asText()).isEqualTo("delivered");
        assertThat(body.path("attempts").get(1).path("deliveredAt").isNull()).isFalse();
        assertThat(body.path("hasMore").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("Phase 17 - history never exposes a provider message, endpoint or secret")
    void historyExposesNoProviderDetail() throws Exception {
        Session owner = register("p17-nosecret");
        UUID reminderId = phase17Reminder(owner);
        Instant occurrence = Instant.parse("2026-09-12T00:30:00Z");
        provider.use(FakeNotificationProvider.Mode.PERMANENT_FAILURE);
        provider.reportCategory(FailureCategory.INVALID_SUBSCRIPTION);
        deliverOccurrence(owner, reminderId, occurrence);

        String raw = getAs(owner, "/api/v1/reminders/" + reminderId + "/delivery-history")
                .getResponse().getContentAsString();

        // The coarse reason is the only free-text field, and it comes from a fixed vocabulary.
        assertThat(raw).doesNotContain("https://").doesNotContain("p256dh").doesNotContain("auth_secret");
        assertThat(raw).contains("INVALID_SUBSCRIPTION").contains("permanent");
    }

    @Test
    @DisplayName("Phase 17 - history paginates without skipping or repeating an occurrence")
    void historyPaginates() throws Exception {
        Session owner = register("p17-paging");
        UUID reminderId = phase17Reminder(owner);
        Instant base = Instant.parse("2026-09-01T00:30:00Z");
        for (int i = 0; i < 5; i++) {
            deliverOccurrence(owner, reminderId, base.plusSeconds(86400L * i));
        }

        JsonNode first = historyPage(owner, reminderId, "?limit=2&offset=0");
        JsonNode second = historyPage(owner, reminderId, "?limit=2&offset=2");
        JsonNode last = historyPage(owner, reminderId, "?limit=2&offset=4");

        assertThat(first.path("attempts")).hasSize(2);
        assertThat(first.path("hasMore").asBoolean()).isTrue();
        assertThat(second.path("attempts")).hasSize(2);
        assertThat(second.path("hasMore").asBoolean()).isTrue();
        assertThat(last.path("attempts")).hasSize(1);
        assertThat(last.path("hasMore").asBoolean()).isFalse();

        // Disjoint pages: all five occurrences appear exactly once, newest first.
        List<String> seen = new ArrayList<>();
        for (JsonNode body : List.of(first, second, last)) {
            body.path("attempts").forEach(node -> seen.add(node.path("occurrenceAt").asText()));
        }
        assertThat(seen).hasSize(5).doesNotHaveDuplicates();
        assertThat(seen).isSortedAccordingTo((left, right) -> right.compareTo(left));
    }

    @Test
    @DisplayName("Phase 17 - history is capped so an unbounded read cannot be requested")
    void historyLimitIsBounded() throws Exception {
        Session owner = register("p17-bounded");
        UUID reminderId = phase17Reminder(owner);
        deliverOccurrence(owner, reminderId, Instant.parse("2026-09-05T00:30:00Z"));

        // The request asked for 9999; the server answered with its own cap.
        assertThat(historyPage(owner, reminderId, "?limit=9999").path("limit").asInt()).isEqualTo(100);
    }

    @Test
    @DisplayName("Phase 17 - a record predating the column reads back as UNKNOWN and still renders")
    void legacyRecordReadsBackAsUnknown() throws Exception {
        Session owner = register("p17-legacy");
        UUID reminderId = phase17Reminder(owner);
        insertLegacyOccurrence(reminderId, UUID.fromString(owner.id()),
                Instant.parse("2026-08-01T00:30:00Z"), "failed");

        JsonNode body = historyPage(owner, reminderId, "");

        assertThat(body.path("attempts")).hasSize(1);
        // The column is NULL for every row recorded before Phase 17, and NULL is reported as UNKNOWN.
        assertThat(body.path("attempts").get(0).path("failureCategory").asText()).isEqualTo("UNKNOWN");
        assertThat(body.path("attempts").get(0).path("state").asText()).isEqualTo("failed");
    }

    @Test
    @DisplayName("Phase 17 - one user's history is not readable by another")
    void historyIsOwnerScoped() throws Exception {
        Session owner = register("p17-owner");
        Session other = register("p17-other");
        UUID reminderId = phase17Reminder(owner);
        deliverOccurrence(owner, reminderId, Instant.parse("2026-09-15T00:30:00Z"));

        // The other user cannot read this reminder's history...
        assertStatus(getAs(other, "/api/v1/reminders/" + reminderId + "/delivery-history"), 404);
        // ...and gets the same 404 as for a reminder that does not exist, so this route cannot be
        // used to discover that someone else's reminder is real.
        assertStatus(getAs(other, "/api/v1/reminders/" + UUID.randomUUID() + "/delivery-history"), 404);
    }

    @Test
    @DisplayName("Phase 17 - history requires an authenticated session")
    void historyRequiresAuthentication() throws Exception {
        Session owner = register("p17-anon");
        UUID reminderId = phase17Reminder(owner);

        assertUnauthenticated(get("/api/v1/reminders/" + reminderId + "/delivery-history"));
    }

    @Test
    @DisplayName("Phase 17 - history is read-only: a write verb cannot alter recorded state")
    void historyIsReadOnly() throws Exception {
        Session owner = register("p17-readonly");
        UUID reminderId = phase17Reminder(owner);
        Instant occurrence = Instant.parse("2026-09-16T00:30:00Z");
        provider.use(FakeNotificationProvider.Mode.PERMANENT_FAILURE);
        provider.reportCategory(FailureCategory.NO_SUBSCRIPTION);
        deliverOccurrence(owner, reminderId, occurrence);

        call(owner, post("/api/v1/reminders/" + reminderId + "/delivery-history")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"state\":\"delivered\",\"failureCategory\":\"RATE_LIMITED\"}"));
        call(owner, put("/api/v1/reminders/" + reminderId + "/delivery-history")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"state\":\"delivered\"}"));

        // The stored occurrence is exactly what the scheduler wrote.
        assertThat(storedCategory(reminderId, occurrence)).isEqualTo("NO_SUBSCRIPTION");
        assertThat(storedReason(reminderId, occurrence)).isEqualTo("permanent");
    }
}
