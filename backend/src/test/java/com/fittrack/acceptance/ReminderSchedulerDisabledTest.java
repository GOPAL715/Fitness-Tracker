package com.fittrack.acceptance;

import com.fittrack.acceptance.support.AbstractAcceptanceTest;
import com.fittrack.acceptance.support.FakeNotificationProvider;
import com.fittrack.reminder.ReminderDeliveryScheduler;
import com.fittrack.reminder.ReminderDeliveryService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 12: the scheduler's off switch.
 *
 * <p>A separate class because the property is class-level configuration, and a separate context is the
 * honest way to show it.
 *
 * <p>Note what this deliberately does <em>not</em> assert: that no bean exists. The component is always
 * wired, so the same deployment can be turned on and off by a property, without a code change and
 * without the two states diverging. A disabled scheduler is one that does nothing when ticked, not one
 * that is absent - and that distinction is what the startup log and this test are both about.
 */
@SpringBootTest(classes = com.fittrack.api.FitTrackApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.reminders.scheduler.enabled=false",
        "app.reminders.scheduler.interval=P1D"
})
class ReminderSchedulerDisabledTest extends AbstractAcceptanceTest {

    private static final Instant NOW = Instant.parse("2026-09-29T20:00:00Z");

    @Autowired ReminderDeliveryScheduler wiredScheduler;

    @Test
    @DisplayName("Phase 12 scheduler - enabled=false stops delivery, rescheduling and claiming alike")
    void disabledSchedulerDeliversNothing() throws Exception {
        var owner = register("sched-off-");
        UUID id = reminder(owner, "06:00", "UTC", "daily");
        Instant occurrence = NOW.minusSeconds(60);
        jdbc.update("update reminders set next_occurrence_at=? where id=?", Timestamp.from(occurrence), id);

        // The Spring-wired bean, configured off. Ticked by hand because the interval is a day, for the
        // reason the enabled suite documents: the trigger is the only thing under test, not the timer.
        assertThat(wiredScheduler.enabled()).as("the flag reached the bean").isFalse();
        wiredScheduler.tick();
        wiredScheduler.tick();

        // The same object again, built with the flag off, so the behaviour is pinned to the flag rather
        // than to one instance's state.
        var channel = new FakeNotificationProvider();
        var disabled = new ReminderDeliveryScheduler(
                new ReminderDeliveryService(jdbc, channel, 3),
                Clock.fixed(NOW, ZoneOffset.UTC), false, "PT60S");
        disabled.tick();

        assertThat(channel.callCount()).as("a disabled scheduler contacts no channel at all").isZero();
        assertThat(jdbc.queryForObject("select count(*) from reminder_deliveries where reminder_id=?",
                Integer.class, id)).as("nothing was claimed either").isZero();
        assertThat(jdbc.queryForObject("select next_occurrence_at from reminders where id=?",
                Timestamp.class, id).toInstant())
                .as("and the schedule was untouched, so enabling later does not skip the occurrence")
                .isEqualTo(occurrence);
    }

    private UUID reminder(Session owner, String time, String zone, String recurrence) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>(Map.of(
                "type", "workout", "title", "Time to train", "message", "Session starts now",
                "scheduled_time", time, "days_of_week", "1,2,3,4,5,6,0",
                "timezone", zone, "recurrence", recurrence, "enabled", true));
        MvcResult result = call(owner, post("/api/v1/reminders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(payload)));
        assertThat(result.getResponse().getStatus()).isIn(200, 201);
        return UUID.fromString(json(result).path("id").asText());
    }
}
