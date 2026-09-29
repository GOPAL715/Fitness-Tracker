package com.fittrack.acceptance;

import com.fittrack.acceptance.support.AbstractAcceptanceTest;
import com.fittrack.acceptance.support.FakeNotificationProvider;
import com.fittrack.reminder.NotificationPreferencesService;
import com.fittrack.reminder.ReminderDeliveryScheduler;
import com.fittrack.reminder.ReminderDeliveryService;
import com.fittrack.reminder.ReminderNotificationPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Phase 19: user-level notification preferences and quiet hours.
 *
 * <p>Runs against a real PostgreSQL with the real scheduler, a deterministic fake channel and a
 * pinned clock, so the properties under test are the ones a user would notice: their reminders stop
 * arriving when they asked them to, a quiet-hours occurrence is not lost, and nothing they did here
 * is reported to them as a delivery failure.
 */
@SpringBootTest(classes = com.fittrack.api.FitTrackApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.reminders.max-attempts=3")
class NotificationPreferencesAcceptanceTest extends AbstractAcceptanceTest {

    /** Pinned so "is this instant inside quiet hours" is a fact about the test, not about the clock. */
    static final Instant NOW = Instant.parse("2026-09-29T20:00:00Z");

    // No @TestConfiguration at all. This suite drives the real scheduler against a per-test channel
    // and an explicitly injected clock, so a bean here would be unused - and its method names would
    // collide with the delivery suite's identically named @Primary ones, which Spring rejects when
    // two @TestConfiguration classes land in the same cached context.

    private FakeNotificationProvider channel;
    private ReminderDeliveryService service;
    private ReminderDeliveryScheduler scheduler;

    @BeforeEach
    void buildScheduler() {
        channel = new FakeNotificationProvider();
        service = new ReminderDeliveryService(jdbc, channel, new NotificationPreferencesService(jdbc), 3);
        scheduler = new ReminderDeliveryScheduler(service, Clock.fixed(NOW, ZoneOffset.UTC), true, "PT60S");
    }

    // ------------------------------------------------------------- helpers

    private static final String PATH = "/api/v1/notification-preferences";

    private MvcResult putPrefs(Session user, Map<String, Object> body) throws Exception {
        return call(user, put(PATH).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)));
    }

    private Map<String, Object> prefs(Object push, Object reminders, Object quiet,
                                      Object start, Object end, Object zone) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pushEnabled", push);
        body.put("reminderNotificationsEnabled", reminders);
        body.put("quietHoursEnabled", quiet);
        if (start != null) body.put("quietHoursStart", start);
        if (end != null) body.put("quietHoursEnd", end);
        if (zone != null) body.put("timezone", zone);
        return body;
    }

    private UUID createReminder(Session owner, String time, String zone) throws Exception {
        MvcResult result = call(owner, post("/api/v1/reminders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new LinkedHashMap<>(Map.of(
                        "type", "workout", "title", "Time to train", "message", "Starts now",
                        "scheduled_time", time, "days_of_week", "1,2,3,4,5,6,0",
                        "timezone", zone, "recurrence", "daily", "enabled", true)))));
        assertStatus(result, 200);
        return UUID.fromString(json(result).path("id").asText());
    }

    private void occurAt(UUID reminderId, Instant occurrence) {
        jdbc.update("update reminders set next_occurrence_at=? where id=?",
                Timestamp.from(occurrence), reminderId);
    }

    // ------------------------------------------------------------- defaults and isolation

    @Test
    @DisplayName("Phase 19 preferences - a user who has never set any gets working defaults")
    void defaultsAreDelivered() throws Exception {
        Session user = register("prefs-default-");

        MvcResult result = getAs(user, PATH);
        assertStatus(result, 200);
        JsonNode body = json(result);

        assertThat(body.path("pushEnabled").asBoolean())
                .as("delivery is on unless the user turns it off").isTrue();
        assertThat(body.path("reminderNotificationsEnabled").asBoolean()).isTrue();
        assertThat(body.path("quietHoursEnabled").asBoolean()).isFalse();
        // Null, not a substituted zone: a fabricated UTC would quietly misplace every window.
        assertThat(body.path("timezone").isNull())
                .as("an unset zone is reported as unset").isTrue();
    }

    @Test
    @DisplayName("Phase 19 preferences - a read does not create a row")
    void readingDoesNotWrite() throws Exception {
        Session user = register("prefs-nowrite-");

        getAs(user, PATH);

        assertThat(jdbc.queryForObject(
                "select count(*) from user_notification_preferences where user_id=CAST(? AS uuid)",
                Integer.class, user.id()))
                .as("a GET must not write, so a user who never opens settings accumulates nothing")
                .isZero();
    }

    @Test
    @DisplayName("Phase 19 preferences - a saved set round-trips through the API")
    void savedPreferencesRoundTrip() throws Exception {
        Session user = register("prefs-round-");

        MvcResult saved = putPrefs(user, prefs(true, true, true, "22:00", "07:00", "Asia/Kolkata"));
        assertStatus(saved, 200);
        JsonNode body = json(saved);

        assertThat(body.path("quietHoursEnabled").asBoolean()).isTrue();
        assertThat(body.path("quietHoursStart").asText())
                .as("times are HH:mm, the value a person types into a time input").isEqualTo("22:00");
        assertThat(body.path("quietHoursEnd").asText()).isEqualTo("07:00");
        assertThat(body.path("timezone").asText()).isEqualTo("Asia/Kolkata");

        assertThat(json(getAs(user, PATH)).path("quietHoursStart").asText()).isEqualTo("22:00");
    }

    @Test
    @DisplayName("Phase 19 preferences - one user cannot read or write another's")
    void preferencesAreUserScoped() throws Exception {
        Session owner = register("prefs-owner-");
        Session attacker = register("prefs-attacker-");

        assertStatus(putPrefs(owner, prefs(false, true, false, null, null, null)), 200);
        assertStatus(putPrefs(attacker, prefs(true, true, false, null, null, null)), 200);

        assertThat(json(getAs(owner, PATH)).path("pushEnabled").asBoolean())
                .as("the owner's own preference is what they read back").isFalse();
        assertThat(json(getAs(attacker, PATH)).path("pushEnabled").asBoolean())
                .as("a forged user_id in the body is ignored, exactly as push subscriptions do")
                .isTrue();
    }

    @Test
    @DisplayName("Phase 19 preferences - a forged user_id in the body changes nothing")
    void userIdInBodyIsIgnored() throws Exception {
        Session owner = register("prefs-forge-");
        Session victim = register("prefs-victim-");
        assertStatus(putPrefs(victim, prefs(true, true, false, null, null, null)), 200);

        Map<String, Object> forged = prefs(false, false, false, null, null, null);
        forged.put("user_id", victim.id());
        assertStatus(putPrefs(owner, forged), 200);

        assertThat(json(getAs(victim, PATH)).path("pushEnabled").asBoolean())
                .as("the victim keeps their own setting").isTrue();
    }

    @Test
    @DisplayName("Phase 19 preferences - the route requires authentication")
    void requiresAuthentication() throws Exception {
        assertStatus(mvc.perform(get(PATH)).andReturn(), 401);
    }

    // ------------------------------------------------------------- validation

    @Test
    @DisplayName("Phase 19 preferences - an unusable timezone is refused, not repaired")
    void invalidTimezoneIsRejected() throws Exception {
        Session user = register("prefs-zone-");

        assertStatus(putPrefs(user, prefs(true, true, true, "22:00", "07:00", "Mars/Olympus")), 400);
        assertStatus(putPrefs(user, prefs(true, true, true, "22:00", "07:00", "not a zone")), 400);

        // Nothing was stored, so the silent-substitution this guards against cannot have happened.
        assertThat(jdbc.queryForObject(
                "select count(*) from user_notification_preferences where user_id=CAST(? AS uuid)",
                Integer.class, user.id())).isZero();
    }

    @Test
    @DisplayName("Phase 19 preferences - quiet hours without a zone are refused rather than defaulted")
    void quietHoursRequireATimezone() throws Exception {
        Session user = register("prefs-nozone-");

        MvcResult result = putPrefs(user, prefs(true, true, true, "22:00", "07:00", null));
        assertStatus(result, 400);
        assertThat(result.getResponse().getContentAsString())
                .as("the user is told a zone is required, not silently given UTC")
                .contains("timezone");
    }

    @Test
    @DisplayName("Phase 19 preferences - an unusable time is refused")
    void invalidTimeIsRejected() throws Exception {
        Session user = register("prefs-time-");

        assertStatus(putPrefs(user, prefs(true, true, true, "25:00", "07:00", "UTC")), 400);
        assertStatus(putPrefs(user, prefs(true, true, true, "ten", "07:00", "UTC")), 400);
        assertStatus(putPrefs(user, prefs(true, true, true, null, "07:00", "UTC")), 400);
        assertStatus(putPrefs(user, prefs(true, true, true, "22:00", null, "UTC")), 400);
    }

    @Test
    @DisplayName("Phase 19 preferences - start == end is refused as ambiguous")
    void startEqualsEndIsRejected() throws Exception {
        Session user = register("prefs-same-");

        MvcResult result = putPrefs(user, prefs(true, true, true, "22:00", "22:00", "UTC"));
        assertStatus(result, 400);
        assertThat(result.getResponse().getContentAsString()).contains("differ");
    }

    @Test
    @DisplayName("Phase 19 preferences - a non-boolean switch is refused rather than coerced")
    void nonBooleanSwitchIsRejected() throws Exception {
        Session user = register("prefs-coerce-");

        assertStatus(putPrefs(user, prefs("yes", true, false, null, null, null)), 400);
        assertStatus(putPrefs(user, prefs(1, true, false, null, null, null)), 400);
    }

    // ------------------------------------------------------------- delivery behaviour

    @Test
    @DisplayName("Phase 19 preferences - the default user still receives reminders")
    void deliveryStillWorksWhenPreferencesAllowIt() throws Exception {
        Session user = register("prefs-allow-");
        UUID id = createReminder(user, "06:00", "UTC");
        occurAt(id, NOW.minusSeconds(60));

        scheduler.tick();

        assertThat(channel.deliveredReminderIds())
                .as("nothing changed for a user with default preferences").contains(id);
    }

    @Test
    @DisplayName("Phase 19 preferences - push disabled stops delivery without touching the reminder")
    void pushDisabledSuppressesButKeepsTheReminder() throws Exception {
        Session user = register("prefs-pushoff-");
        UUID id = createReminder(user, "06:00", "UTC");
        occurAt(id, NOW.minusSeconds(60));
        assertStatus(putPrefs(user, prefs(false, true, false, null, null, null)), 200);

        scheduler.tick();

        assertThat(channel.deliveredReminderIds())
                .as("the user asked for silence").doesNotContain(id);
        assertThat(jdbc.queryForObject("select enabled from reminders where id=?", Boolean.class, id))
                .as("a user preference is not a reminder state; the reminder stays enabled").isTrue();
        assertThat(jdbc.queryForObject("select count(*) from reminders where id=?", Integer.class, id))
                .as("the reminder is not deleted either").isEqualTo(1);
    }

    @Test
    @DisplayName("Phase 19 preferences - reminder notifications disabled also suppresses")
    void reminderNotificationsDisabledSuppresses() throws Exception {
        Session user = register("prefs-remoff-");
        UUID id = createReminder(user, "06:00", "UTC");
        occurAt(id, NOW.minusSeconds(60));
        assertStatus(putPrefs(user, prefs(true, false, false, null, null, null)), 200);

        scheduler.tick();

        assertThat(channel.deliveredReminderIds())
                .as("reminder notifications are off too").doesNotContain(id);
    }

    /** Structurally valid but inert keys: a 65-byte point and a 16-byte auth secret, per RFC 8291. */
    private static final String P256DH =
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[65]);
    private static final String AUTH =
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);

    @Test
    @DisplayName("Phase 19 preferences - a disabled preference does not delete the device subscriptions")
    void disablingDoesNotRemoveSubscriptions() throws Exception {
        Session user = register("prefs-subsc-");
        String endpoint = "https://fcm.googleapis.com/fcm/send/phase19-" + UUID.randomUUID();
        assertStatus(call(user, post("/api/v1/push/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                        "endpoint", endpoint, "p256dh", P256DH, "auth", AUTH)))), 200);

        assertStatus(putPrefs(user, prefs(false, false, false, null, null, null)), 200);

        assertThat(jdbc.queryForObject(
                "select count(*) from push_subscriptions where user_id=CAST(? AS uuid) and endpoint=?",
                Integer.class, user.id(), endpoint))
                .as("turning a preference off must not tear down a device the user may re-enable")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Phase 19 preferences - a suppression is not recorded as a provider failure")
    void suppressionIsNotAFailure() throws Exception {
        Session user = register("prefs-skip-");
        UUID id = createReminder(user, "06:00", "UTC");
        Instant occurrence = NOW.minusSeconds(60);
        occurAt(id, occurrence);
        assertStatus(putPrefs(user, prefs(false, true, false, null, null, null)), 200);

        scheduler.tick();

        String state = jdbc.queryForObject(
                "select state from reminder_deliveries where reminder_id=?", String.class, id);
        assertThat(state)
                .as("the user turned notifications off; nothing failed")
                .isEqualTo(ReminderNotificationPolicy.SKIPPED_POLICY);

        assertThat(jdbc.queryForObject(
                "select last_error from reminder_deliveries where reminder_id=?", String.class, id))
                .as("no error is described, because nothing went wrong").isNull();
        assertThat(jdbc.queryForObject(
                "select failure_category from reminder_deliveries where reminder_id=?", String.class, id))
                .as("no Phase 17 category: this is not NO_SUBSCRIPTION or any provider fault").isNull();
    }

    @Test
    @DisplayName("Phase 19 preferences - a suppressed occurrence is recorded once, not every tick")
    void suppressionDoesNotAccumulate() throws Exception {
        Session user = register("prefs-skip2-");
        UUID id = createReminder(user, "06:00", "UTC");
        occurAt(id, NOW.minusSeconds(60));
        assertStatus(putPrefs(user, prefs(false, true, false, null, null, null)), 200);

        scheduler.tick();
        scheduler.tick();
        scheduler.tick();

        assertThat(jdbc.queryForObject(
                "select count(*) from reminder_deliveries where reminder_id=?", Integer.class, id))
                .as("one occurrence, one row, however many ticks ran")
                .isLessThanOrEqualTo(1);
    }

    // ------------------------------------------------------------- quiet hours

    /** The instant a 22:00-07:00 Asia/Kolkata window closes on the day after the pinned tick. */
    private static final Instant KOLKATA_QUIET_END =
            java.time.LocalDate.parse("2026-09-30").atTime(java.time.LocalTime.of(7, 0))
                    .atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant();

    /** An instant that is inside 22:00-07:00 Asia/Kolkata (01:30 local the next day). */
    private static final Instant DURING_KOLKATA_QUIET =
            java.time.LocalDate.parse("2026-09-30").atTime(java.time.LocalTime.of(1, 30))
                    .atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant();

    @Test
    @DisplayName("Phase 19 quiet hours - an occurrence due inside the window is deferred, not delivered")
    void dueDuringQuietHoursIsDeferred() throws Exception {
        Session user = register("quiet-defer-");
        assertStatus(putPrefs(user, prefs(true, true, true, "22:00", "07:00", "Asia/Kolkata")), 200);
        UUID id = createReminder(user, "01:30", "Asia/Kolkata");
        occurAt(id, DURING_KOLKATA_QUIET.minusSeconds(60));

        scheduler.tick();

        assertThat(channel.deliveredReminderIds())
                .as("nothing is sent at 01:30 local").doesNotContain(id);
        assertThat(jdbc.queryForObject(
                "select count(*) from reminder_deliveries where reminder_id=?", Integer.class, id))
                .as("a deferral is not a claim: the occurrence must still be deliverable later")
                .isZero();
        assertThat(jdbc.queryForObject(
                "select next_occurrence_at from reminders where id=?", Timestamp.class, id).toInstant())
                .as("the schedule moves to the instant the window closes")
                .isEqualTo(KOLKATA_QUIET_END);
    }
    @Test
    @DisplayName("Phase 19 quiet hours - the deferred occurrence is delivered once, at the window's end")
    void deferredOccurrenceIsDeliveredAfterQuietHours() throws Exception {
        Session user = register("quiet-release-");
        assertStatus(putPrefs(user, prefs(true, true, true, "22:00", "07:00", "Asia/Kolkata")), 200);
        UUID id = createReminder(user, "01:30", "Asia/Kolkata");
        Instant occurrence = DURING_KOLKATA_QUIET.minusSeconds(60);
        occurAt(id, occurrence);

        scheduler.tick();
        assertThat(channel.deliveredReminderIds())
                .as("still quiet, so this reminder is untouched").doesNotContain(id);

        // The window has closed: the reminder is due again at 07:00 local and the clock has moved on.
        Instant afterQuiet = KOLKATA_QUIET_END.plusSeconds(60);
        ReminderDeliveryScheduler later = new ReminderDeliveryScheduler(
                service, Clock.fixed(afterQuiet, ZoneOffset.UTC), true, "PT60S");
        later.tick();

        // Scoped to this reminder rather than the whole channel: the suite shares one database, so a
        // sibling test's deferred reminder is legitimately delivered by the same tick. What matters
        // is that this occurrence was delivered exactly once.
        assertThat(channel.deliveredReminderIds())
                .as("delivered once quiet hours ended").containsOnlyOnce(id);
        assertThat(jdbc.queryForObject(
                "select count(*) from reminder_deliveries where reminder_id=?", Integer.class, id))
                .as("exactly one occurrence row: no duplicate from the deferral")
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select occurrence_at from reminder_deliveries where reminder_id=?",
                Timestamp.class, id).toInstant())
                .as("the occurrence is recorded at the instant it actually became deliverable, "
                        + "which is the deterministic quiet-hours end rather than the original "
                        + "scheduled time the user asked to be woken after")
                .isEqualTo(KOLKATA_QUIET_END);
    }

    @Test
    @DisplayName("Phase 19 quiet hours - repeated ticks inside the window never reschedule or send")
    void deferralDoesNotLoop() throws Exception {
        Session user = register("quiet-loop-");
        assertStatus(putPrefs(user, prefs(true, true, true, "22:00", "07:00", "Asia/Kolkata")), 200);
        UUID id = createReminder(user, "01:30", "Asia/Kolkata");
        occurAt(id, DURING_KOLKATA_QUIET.minusSeconds(60));

        // A clock that stays inside the window: every tick must defer, never deliver, never advance
        // past the resume instant into a second window.
        ReminderDeliveryScheduler stuck = new ReminderDeliveryScheduler(
                service, Clock.fixed(DURING_KOLKATA_QUIET, ZoneOffset.UTC), true, "PT60S");
        stuck.tick();
        stuck.tick();
        stuck.tick();
        stuck.tick();

        assertThat(channel.deliveredReminderIds())
                .as("a window that never closes sends nothing").doesNotContain(id);
        assertThat(jdbc.queryForObject(
                "select count(*) from reminder_deliveries where reminder_id=?", Integer.class, id))
                .as("no occurrence is claimed, so nothing can be lost or duplicated").isZero();
    }

    @Test
    @DisplayName("Phase 19 quiet hours - the window is resolved in the user's zone, not the server's")
    void quietHoursUseTheStoredZone() throws Exception {
        // The pinned tick is 2026-09-29T20:00Z, which is 01:30 on the 30th in Asia/Kolkata and
        // 16:00 on the 29th in UTC. A Kolkata window covers the tick; a UTC window must not.
        Session kolkataUser = register("quiet-zone-");
        assertStatus(putPrefs(kolkataUser, prefs(true, true, true, "22:00", "07:00", "Asia/Kolkata")), 200);
        UUID kolkata = createReminder(kolkataUser, "01:30", "Asia/Kolkata");
        occurAt(kolkata, NOW.minusSeconds(60));
        scheduler.tick();
        assertThat(channel.deliveredReminderIds()).as("quiet in Asia/Kolkata").doesNotContain(kolkata);

        // The same wall-clock window in a zone where the same instant is mid-afternoon.
        Session utcUser = register("quiet-zone2-");
        assertStatus(putPrefs(utcUser, prefs(true, true, true, "22:00", "07:00", "UTC")), 200);
        UUID utc = createReminder(utcUser, "16:00", "UTC");
        occurAt(utc, NOW.minusSeconds(60));
        scheduler.tick();
        assertThat(channel.deliveredReminderIds())
                .as("the same window in UTC is not quiet at 20:00Z, so this one is delivered")
                .contains(utc);
    }

    @Test
    @DisplayName("Phase 19 quiet hours - outside the window nothing is suppressed")
    void outsideQuietHoursDelivers() throws Exception {
        Session user = register("quiet-outside-");
        // The pinned tick is 20:00Z = 01:30 Kolkata, so a 02:00-05:00 window does not cover it.
        assertStatus(putPrefs(user, prefs(true, true, true, "02:00", "05:00", "Asia/Kolkata")), 200);
        UUID id = createReminder(user, "01:30", "Asia/Kolkata");
        occurAt(id, NOW.minusSeconds(60));

        scheduler.tick();

        assertThat(channel.deliveredReminderIds())
                .as("the user is outside their own quiet hours").contains(id);
    }

    @Test
    @DisplayName("Phase 19 quiet hours - a disabled preference wins over a quiet-hours window")
    void disabledPreferenceTakesPrecedenceOverQuietHours() throws Exception {
        Session user = register("quiet-both-");
        // Both apply. The preference is checked first, so the occurrence is skipped rather than
        // deferred - a user who muted everything should not have reminders queued for their morning.
        assertStatus(putPrefs(user, prefs(false, true, true, "22:00", "07:00", "Asia/Kolkata")), 200);
        UUID id = createReminder(user, "01:30", "Asia/Kolkata");
        occurAt(id, DURING_KOLKATA_QUIET.minusSeconds(60));

        scheduler.tick();

        assertThat(jdbc.queryForObject(
                "select state from reminder_deliveries where reminder_id=?", String.class, id))
                .isEqualTo(ReminderNotificationPolicy.SKIPPED_POLICY);
    }

    @Test
    @DisplayName("Phase 19 - a reminder left disabled is still not delivered, whatever the preferences")
    void disabledReminderRemainsIndependent() throws Exception {
        Session user = register("indep-off-");
        // Preferences fully permissive; the reminder itself is off.
        assertStatus(putPrefs(user, prefs(true, true, false, null, null, null)), 200);
        UUID id = createReminder(user, "06:00", "UTC");
        jdbc.update("update reminders set enabled=false where id=?", id);
        occurAt(id, NOW.minusSeconds(60));

        scheduler.tick();

        assertThat(channel.deliveredReminderIds())
                .as("a paused reminder is not resurrected by permissive preferences").doesNotContain(id);
    }
}
