package com.fittrack.acceptance;

import com.fittrack.acceptance.support.AbstractAcceptanceTest;
import com.fittrack.acceptance.support.FakeNotificationProvider;
import com.fittrack.reminder.ReminderDeliveryScheduler;
import com.fittrack.reminder.ReminderDeliveryService;
import com.fittrack.reminder.NotificationPreferencesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 12: the scheduler that drives the existing delivery pipeline.
 *
 * <p><strong>How this stays deterministic.</strong> Two things could otherwise make these assertions
 * depend on the machine rather than on the code.
 *
 * <p>First, time. The reference instant is pinned with a {@link Clock#fixed} clock at
 * 2026-09-29T20:00Z, so "is this due" and "what comes next" are computed rather than observed.
 *
 * <p>Second, the timer. A poll that fires on a wall-clock schedule would let the outcome depend on how
 * loaded the runner is, and a cached Spring context lives for the whole JVM, so a 60-second tick could
 * fire in the middle of an unrelated test class and deliver somebody's reminder mid-assertion. The
 * interval is therefore pinned to a day - which is also the initial delay, so the timer cannot fire
 * during a build - and each test invokes {@code tick()} directly. That is the same method Spring's
 * scheduler invokes; only the trigger differs. That the timer is registered at all is a wiring fact,
 * asserted structurally in {@link #schedulerIsWiredAsASpringManagedTimer()}, rather than by waiting
 * for a clock to advance.
 *
 * <p><strong>Why the scheduler is built here rather than injected.</strong> This suite registers no
 * test configuration classes on purpose. {@code FitTrackApplication} declares an explicit
 * {@code @ComponentScan("com.fittrack")}, which replaces the scan {@code @SpringBootApplication}
 * configures and therefore drops its {@code TypeExcludeFilter} - so every {@code @TestConfiguration}
 * in the test tree is component-scanned into <em>every</em> context. Any additional pinned clock or
 * delivery channel declared here would collide with the ones other suites already contribute, and the
 * failure would be a context-startup error saying nothing about scheduling. Constructing the scheduler
 * against this test's own clock and channel keeps the subject self-contained; the Spring-wired
 * instance is covered separately by the wiring test.
 */
@SpringBootTest(classes = com.fittrack.api.FitTrackApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.reminders.scheduler.enabled=true",
        // A day, so the timer cannot fire mid-suite; see the class note. tick() is driven by hand.
        "app.reminders.scheduler.interval=P1D"
})
class ReminderSchedulerAcceptanceTest extends AbstractAcceptanceTest {

    /** The pinned reference instant, matching the instant the Phase 15 delivery suite uses. */
    private static final Instant NOW = Instant.parse("2026-09-29T20:00:00Z");

    /**
     * The delivery channel, observable and reusable.
     *
     * <p>Extends the suite's existing fake so recording, outcome modes and reset semantics are not
     * reimplemented. The addition is a gate: when armed, a call waits inside the channel until
     * released. That is what lets an overlapping tick be <em>created on purpose</em> - waiting for a
     * slow provider and a short interval to coincide by chance would be a flaky test dressed up as a
     * concurrency test.
     */
    static final class GatedNotificationProvider extends FakeNotificationProvider {

        /** Calls that entered the channel, including one still waiting at the gate. */
        private final AtomicInteger arrivals = new AtomicInteger();
        private volatile boolean gated;
        private volatile CountDownLatch entered = new CountDownLatch(1);
        private volatile CountDownLatch release = new CountDownLatch(1);

        void gateNextCall() {
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
            gated = true;
        }

        boolean awaitArrival() throws InterruptedException {
            return entered.await(20, TimeUnit.SECONDS);
        }

        void releaseGate() { release.countDown(); }

        int arrivals() { return arrivals.get(); }

        @Override
        public void reset() {
            gated = false;
            arrivals.set(0);
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
            super.reset();
        }

        @Override
        public Outcome deliver(DeliveryRequest request) {
            arrivals.incrementAndGet();
            if (gated) {
                entered.countDown();
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("gate wait interrupted", e);
                }
            }
            return super.deliver(request);
        }
    }

    @Autowired ReminderDeliveryScheduler wiredScheduler;
    @Autowired Clock contextClock;

    private GatedNotificationProvider channel;
    private ReminderDeliveryService service;
    private ReminderDeliveryScheduler scheduler;

    /**
     * Builds the subject: the real service and the real scheduler, wired to this test's own channel and
     * a fixed clock. Everything the scheduler does at runtime happens here; only the container and the
     * clock differ from production.
     */
    @BeforeEach
    void buildScheduler() {
        channel = new GatedNotificationProvider();
        // Phase 19: the real preferences service, so these Phase 12 scheduler cases run through the
        // policy gate with the shipped defaults rather than bypassing it.
        service = new ReminderDeliveryService(jdbc, channel, new NotificationPreferencesService(jdbc), 3);
        scheduler = new ReminderDeliveryScheduler(service, Clock.fixed(NOW, ZoneOffset.UTC), true, "PT60S");
    }

    // --------------------------------------------------------------- helpers

    private UUID createReminder(Session owner, String time, String zone, String recurrence, boolean enabled)
            throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>(Map.of(
                "type", "workout", "title", "Time to train", "message", "Session starts now",
                "scheduled_time", time, "days_of_week", "1,2,3,4,5,6,0",
                "timezone", zone, "recurrence", recurrence, "enabled", enabled));
        MvcResult result = call(owner, post("/api/v1/reminders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(payload)));
        assertThat(result.getResponse().getStatus())
                .as("reminder create body was %s", result.getResponse().getContentAsString())
                .isIn(200, 201);
        return UUID.fromString(json(result).path("id").asText());
    }

    /**
     * Sets the stored occurrence directly instead of going through the rescheduling endpoint.
     *
     * <p>The endpoint derives the occurrence from the injected clock, so it can only ever produce an
     * instant in the future relative to that clock, while a scheduler test needs an occurrence that is
     * <em>due</em>. Writing the column is also how the exact boundary is placed - the pinned instant
     * itself, and one millisecond either side of it.
     */
    private void occurAt(UUID reminderId, Instant occurrence) {
        jdbc.update("update reminders set next_occurrence_at=? where id=?",
                Timestamp.from(occurrence), reminderId);
    }

    private Instant nextOccurrenceOf(UUID reminderId) {
        return jdbc.queryForObject("select next_occurrence_at from reminders where id=?",
                Timestamp.class, reminderId).toInstant();
    }

    private List<Map<String, Object>> deliveryRows(UUID reminderId) {
        return jdbc.queryForList("select occurrence_at,state,attempts from reminder_deliveries"
                + " where reminder_id=? order by occurrence_at", reminderId);
    }

    private String reminderDeliveryState(UUID reminderId) {
        return jdbc.queryForObject("select delivery_status from reminders where id=?", String.class, reminderId);
    }

    // ----------------------------------------------------------------- tests

    @Test
    @DisplayName("Phase 12 scheduler - the application wires one as a Spring-managed timer on the injected clock")
    void schedulerIsWiredAsASpringManagedTimer() throws Exception {
        Scheduled scheduled = ReminderDeliveryScheduler.class.getMethod("tick").getAnnotation(Scheduled.class);

        assertThat(scheduled)
                .as("without @Scheduled nothing polls, however correct the tick logic is").isNotNull();
        assertThat(scheduled.fixedDelayString())
                .as("the interval is configuration, not a constant in the annotation")
                .isEqualTo("${app.reminders.scheduler.interval:PT60S}");
        assertThat(scheduled.initialDelayString())
                .as("a starting instance waits one interval instead of working through a backlog while it boots")
                .isEqualTo("${app.reminders.scheduler.interval:PT60S}");

        assertThat(wiredScheduler)
                .as("the application context holds the scheduler, and enabled=true reached the bean")
                .isNotNull();
        assertThat(wiredScheduler.enabled()).isTrue();
        assertThat(ReflectionTestUtils.getField(wiredScheduler, "clock"))
                .as("the container-injected clock is the application's Clock bean, not a static wall-clock read")
                .isSameAs(contextClock);
    }

    @Test
    @DisplayName("Phase 12 scheduler - a tick delivers a due occurrence and advances the schedule")
    void tickDeliversDueOccurrenceAndAdvances() throws Exception {
        var owner = register("sched-due-");
        UUID id = createReminder(owner, "06:00", "UTC", "daily", true);
        Instant occurrence = NOW.minusSeconds(60);
        occurAt(id, occurrence);

        scheduler.tick();

        assertThat(channel.callCount()).as("the tick reached the channel exactly once").isEqualTo(1);
        assertThat(channel.attempts()).singleElement().satisfies(request -> {
            assertThat(request.reminderId()).isEqualTo(id);
            assertThat(request.occurrence())
                    .as("the occurrence handed to the channel is the stored one, not a recomputed instant")
                    .isEqualTo(occurrence);
        });
        assertThat(deliveryRows(id)).singleElement().satisfies(row -> {
            assertThat(row.get("state")).isEqualTo("delivered");
            assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(1);
        });
        assertThat(reminderDeliveryState(id)).isEqualTo("delivered");
        assertThat(nextOccurrenceOf(id))
                .as("06:00 UTC daily, searched forward from the tick instant 2026-09-29T20:00Z")
                .isEqualTo(Instant.parse("2026-09-30T06:00:00Z"));
    }

    @Test
    @DisplayName("Phase 12 scheduler - a reminder that is not due yet is left completely alone")
    void tickLeavesFutureOccurrencesAlone() throws Exception {
        var owner = register("sched-future-");
        UUID id = createReminder(owner, "06:00", "UTC", "daily", true);
        Instant notDueYet = NOW.plusSeconds(1);
        occurAt(id, notDueYet);

        scheduler.tick();

        assertThat(channel.callCount()).as("nothing was sent").isZero();
        assertThat(deliveryRows(id)).as("no occurrence was claimed").isEmpty();
        assertThat(nextOccurrenceOf(id)).isEqualTo(notDueYet);
    }

    @Test
    @DisplayName("Phase 12 scheduler - the boundary is the injected clock, not the wall clock")
    void tickJudgesDueNessAgainstTheInjectedClock() throws Exception {
        var owner = register("sched-boundary-");
        UUID due = createReminder(owner, "06:00", "UTC", "daily", true);
        UUID notDue = createReminder(owner, "07:00", "UTC", "daily", true);
        // Exactly one millisecond either side of the pinned instant. The machine's own clock is nowhere
        // near either value, so a scheduler reading it statically would reach a different answer here.
        occurAt(due, NOW);
        occurAt(notDue, NOW.plusMillis(1));

        scheduler.tick();

        assertThat(channel.deliveredReminderIds())
                .as("the stored instant itself is due; one millisecond later is not")
                .containsExactly(due);
        assertThat(deliveryRows(notDue)).isEmpty();
        assertThat(nextOccurrenceOf(notDue)).isEqualTo(NOW.plusMillis(1));
    }

    @Test
    @DisplayName("Phase 12 scheduler - a disabled reminder is not delivered or rescheduled")
    void tickSkipsDisabledReminders() throws Exception {
        var owner = register("sched-disabled-reminder-");
        UUID id = createReminder(owner, "06:00", "UTC", "daily", false);
        Instant occurrence = NOW.minusSeconds(60);
        occurAt(id, occurrence);

        scheduler.tick();

        assertThat(channel.callCount()).isZero();
        assertThat(deliveryRows(id)).isEmpty();
        assertThat(nextOccurrenceOf(id)).isEqualTo(occurrence);
    }

    @Test
    @DisplayName("Phase 12 scheduler - an empty queue is a no-op")
    void tickWithNothingDueDoesNothing() {
        scheduler.tick();

        assertThat(channel.callCount()).isZero();
    }

    @Test
    @DisplayName("Phase 12 scheduler - a processed occurrence is never delivered again by later ticks")
    void repeatedTicksDoNotRedeliverAProcessedOccurrence() throws Exception {
        var owner = register("sched-repeat-");
        UUID id = createReminder(owner, "06:00", "UTC", "daily", true);
        occurAt(id, NOW.minusSeconds(60));

        scheduler.tick();
        scheduler.tick();
        scheduler.tick();

        assertThat(channel.callCount())
                .as("advancing the schedule is what stops the next tick, not a memory of what was sent")
                .isEqualTo(1);
        assertThat(deliveryRows(id)).hasSize(1);
    }

    @Test
    @DisplayName("Phase 12 scheduler - a rejected occurrence is advanced, not retried on every tick")
    void rejectedOccurrenceIsAdvancedRatherThanRetriedIndefinitely() throws Exception {
        var owner = register("sched-rejected-");
        UUID id = createReminder(owner, "06:00", "UTC", "daily", true);
        occurAt(id, NOW.minusSeconds(60));
        channel.use(FakeNotificationProvider.Mode.PERMANENT_FAILURE);

        scheduler.tick();
        scheduler.tick();

        assertThat(deliveryRows(id)).singleElement().satisfies(row -> {
            assertThat(row.get("state")).isEqualTo("failed");
            assertThat(((Number) row.get("attempts")).intValue())
                    .as("a permanent failure is never retried inside the service").isEqualTo(1);
        });
        assertThat(nextOccurrenceOf(id))
                .as("the schedule moved on, so a rejecting channel cannot retry the same occurrence forever")
                .isEqualTo(Instant.parse("2026-09-30T06:00:00Z"));
    }

    @Test
    @DisplayName("Phase 12 scheduler - an occurrence already in the ledger is not sent again")
    void alreadyAccountedOccurrenceIsNotRedelivered() throws Exception {
        var owner = register("sched-accounted-");
        UUID id = createReminder(owner, "06:00", "UTC", "daily", true);
        Instant occurrence = NOW.minusSeconds(60);
        occurAt(id, occurrence);
        // Something else - an operator action, a restored backup, a second instance - already claimed
        // this occurrence. The scheduler's job is to be a no-op here, not to override the ledger.
        service.deliver(id, UUID.fromString(owner.id()), "Time to train", "Session starts now", occurrence);
        assertThat(channel.callCount()).as("the ledger entry is what was sent").isEqualTo(1);

        scheduler.tick();

        assertThat(channel.callCount()).as("the channel was not called a second time").isEqualTo(1);
        assertThat(deliveryRows(id)).hasSize(1);
    }

    @Test
    @DisplayName("Phase 12 scheduler - the next occurrence is computed in the reminder's own zone")
    void nextOccurrenceIsComputedInTheUsersTimezone() throws Exception {
        var owner = register("sched-zone-");
        // 07:00 in Asia/Kolkata is 01:30Z. The tick instant 2026-09-29T20:00Z is 2026-09-30 01:30
        // locally, so today's 07:00 has passed and the next one is 2026-09-30T01:30Z. A server-side zone
        // would give a different answer, which is what makes this a real assertion.
        UUID id = createReminder(owner, "07:00", "Asia/Kolkata", "daily", true);
        occurAt(id, NOW.minusSeconds(60));

        scheduler.tick();

        assertThat(channel.callCount()).isEqualTo(1);
        assertThat(nextOccurrenceOf(id)).isEqualTo(Instant.parse("2026-09-30T01:30:00Z"));
    }

    @Test
    @DisplayName("Phase 12 scheduler - a one-time reminder is delivered once and then stops being due")
    void oneTimeReminderIsDeliveredOnceAndStopsBeingDue() throws Exception {
        var owner = register("sched-once-");
        UUID id = createReminder(owner, "06:00", "UTC", "once", true);
        occurAt(id, NOW.minusSeconds(60));

        scheduler.tick();
        scheduler.tick();

        assertThat(channel.callCount()).isEqualTo(1);
        assertThat(deliveryRows(id)).hasSize(1);
        // The roll-forward is the shared helper's existing behaviour, identical to what the rescheduling
        // endpoint already returns: today's 06:00 has passed at 20:00Z, so the following day's is next.
        // What matters here is that it is in the future, which is what stops the second tick.
        assertThat(nextOccurrenceOf(id)).isEqualTo(Instant.parse("2026-09-30T06:00:00Z"));
    }

    @Test
    @DisplayName("Phase 12 scheduler - a reminder it cannot reschedule is still delivered, then left due")
    void unreschedulableReminderIsDeliveredAndLeftDue() throws Exception {
        var owner = register("sched-unusable-");
        UUID broken = createReminder(owner, "06:00", "UTC", "daily", true);
        UUID healthy = createReminder(owner, "07:00", "UTC", "daily", true);
        occurAt(broken, NOW.minusSeconds(60));
        occurAt(healthy, NOW.minusSeconds(60));
        // A row the scheduler cannot compute a following occurrence for. The failure has to stay local:
        // one bad row must not cost every other user their reminder.
        jdbc.update("update reminders set scheduled_time=null where id=?", broken);

        scheduler.tick();

        // The notification still goes out. Delivery is the user-visible outcome, and it does not depend
        // on the schedule being computable - only the advance afterwards does.
        assertThat(channel.deliveredReminderIds())
                .as("both reminders were delivered; the broken one failed afterwards, not before")
                .containsExactlyInAnyOrder(broken, healthy);
        assertThat(nextOccurrenceOf(healthy)).as("the healthy reminder was rescheduled normally")
                .isEqualTo(Instant.parse("2026-09-30T07:00:00Z"));
        assertThat(nextOccurrenceOf(broken))
                .as("the broken row is left due, so a later tick retries it once it is repaired")
                .isEqualTo(NOW.minusSeconds(60));

        // Left due, but not re-sent: the occurrence is already accounted for, so the next tick's claim
        // loses to the unique key and the channel is not contacted again. This is why an unreschedulable
        // row is a stuck schedule rather than a repeated notification.
        scheduler.tick();
        assertThat(channel.callCount()).as("still one call per reminder after the second tick").isEqualTo(2);
        assertThat(deliveryRows(broken)).hasSize(1);
    }

    // ------------------------------------------------------- overlap and instances

    @Test
    @DisplayName("Phase 12 scheduler - a tick that starts while another is running is skipped, not queued")
    void overlappingTickOnOneInstanceIsSkipped() throws Exception {
        var owner = register("sched-overlap-");
        UUID id = createReminder(owner, "06:00", "UTC", "daily", true);
        occurAt(id, NOW.minusSeconds(60));
        channel.gateNextCall();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> inFlight = pool.submit(scheduler::tick);
            assertThat(channel.awaitArrival())
                    .as("the first tick reached the channel and is held there").isTrue();

            // The guard is what makes a second concurrent tick a no-op. Without it this call would block
            // on the first tick's channel call, and a queued second pass would rescan the same queue
            // moments later for no reason.
            scheduler.tick();

            assertThat(channel.arrivals())
                    .as("the second tick never reached the channel").isEqualTo(1);

            channel.releaseGate();
            inFlight.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(channel.callCount()).isEqualTo(1);
        assertThat(deliveryRows(id)).hasSize(1);
        assertThat(nextOccurrenceOf(id)).isEqualTo(Instant.parse("2026-09-30T06:00:00Z"));

        // The guard is released when a tick ends, so the scheduler is not left permanently wedged.
        UUID later = createReminder(owner, "07:00", "UTC", "daily", true);
        occurAt(later, NOW.minusSeconds(60));
        scheduler.tick();
        assertThat(channel.deliveredReminderIds()).containsExactlyInAnyOrder(id, later);
    }

    @Test
    @DisplayName("Phase 12 scheduler - two instances racing on one occurrence deliver it once")
    void twoInstancesOnTheSameOccurrenceDeliverOnce() throws Exception {
        var owner = register("sched-two-instances-");
        UUID id = createReminder(owner, "06:00", "UTC", "daily", true);
        occurAt(id, NOW.minusSeconds(60));

        // A second instance, as a second process would be. The in-process guard cannot help here - it
        // has never heard of the other JVM - so this is the case the database constraint exists for.
        var second = new ReminderDeliveryScheduler(service, Clock.fixed(NOW, ZoneOffset.UTC), true, "PT60S");
        var barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> one = pool.submit(() -> { barrier.await(20, TimeUnit.SECONDS); scheduler.tick(); return null; });
            Future<?> two = pool.submit(() -> { barrier.await(20, TimeUnit.SECONDS); second.tick(); return null; });
            one.get(60, TimeUnit.SECONDS);
            two.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(channel.callCount())
                .as("exactly one instance reached the channel; the loser lost the claim insert")
                .isEqualTo(1);
        assertThat(deliveryRows(id)).as("one row in the ledger, so no duplicate accounting").hasSize(1);
    }
}
