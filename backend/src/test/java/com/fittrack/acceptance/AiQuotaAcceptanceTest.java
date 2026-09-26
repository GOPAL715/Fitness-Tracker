package com.fittrack.acceptance;

import com.fittrack.acceptance.support.AiAssertions;
import com.fittrack.acceptance.support.FakeAiProvider.Mode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 14 7-8: quota rejection and concurrent quota enforcement.
 *
 * <p>This context pins {@code requests-per-minute=1} so the limit is deterministic rather than
 * dependent on production defaults. Quota enforcement runs against the real PostgreSQL counter
 * table and is never mocked away.
 */
@SpringBootTest(classes = com.fittrack.api.FitTrackApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.ai-limits.requests-per-minute=1",
        "app.ai-limits.requests-per-day=50"
})
class AiQuotaAcceptanceTest extends AiAssertions {

    private MvcResult coach(Session user) throws Exception {
        return call(user, post("/api/v1/coach/analyze").contentType(MediaType.APPLICATION_JSON).content("{}"));
    }

    // ------------------------------------------------------------ 7 rejection

    @Test
    @DisplayName("Phase 14 item 7 - the first AI request succeeds and the second is rejected with 429")
    void quotaRejectsTheSecondRequestWithoutCallingTheProvider() throws Exception {
        Session user = register("quota-seq-");
        fake().use(Mode.SUCCESS_WITH_TOKENS);

        MvcResult first = coach(user);
        assertStatus(first, 200);
        assertThat(fake().coachCalls()).as("the first request reaches the provider").isEqualTo(1);

        MvcResult second = coach(user);
        assertStructuredError(second, 429, "Too Many Requests");

        assertThat(fake().coachCalls())
                .as("a rejected request must never reach the provider").isEqualTo(1);
        assertThat(minuteCounter(user.id(), "weekly_coach"))
                .as("the rejected request must not consume quota").isEqualTo(1);
        assertThat(dayCounter(user.id(), "weekly_coach")).isEqualTo(1);
    }

    @Test
    @DisplayName("Phase 14 item 7 - quota rejection writes its own failure row with a quota category")
    void quotaRejectionIsAccounted() throws Exception {
        Session user = register("quota-reject-");
        fake().use(Mode.SUCCESS_WITH_TOKENS);

        assertStatus(coach(user), 200);
        assertStructuredError(coach(user), 429, "Too Many Requests");

        List<Map<String, Object>> rows = usageRows(user.id());
        assertThat(rows).hasSize(2);
        Map<String, Object> rejected = rows.stream()
                .filter(r -> Boolean.FALSE.equals(r.get("success")))
                .findFirst().orElseThrow();
        assertThat(rejected.get("user_id").toString()).isEqualTo(user.id());
        assertThat(rejected.get("feature")).isEqualTo("weekly_coach");
        assertThat(rejected.get("error_category")).isEqualTo("quota");
        assertThat(rejected.get("total_tokens")).isNull();
        assertThat(rejected.get("estimated_cost")).isNull();
        assertThat(rows.stream().filter(r -> Boolean.TRUE.equals(r.get("success")))).hasSize(1);
    }

    @Test
    @DisplayName("Phase 14 item 7 - quota is scoped per user, so a second user is unaffected")
    void quotaIsScopedPerUser() throws Exception {
        Session first = register("quota-scope-a-");
        Session second = register("quota-scope-b-");
        fake().use(Mode.SUCCESS_WITH_TOKENS);

        assertStatus(coach(first), 200);
        assertStructuredError(coach(first), 429, "Too Many Requests");
        assertStatus(coach(second), 200);

        assertThat(fake().coachCalls()).isEqualTo(2);
    }

    // ------------------------------------------------------------ item 8 concurrency

    /** Fires {@code attempts} simultaneous coach requests and returns their HTTP statuses. */
    private List<Integer> race(Session user, int attempts) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(attempts);
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            List<Callable<Integer>> tasks = IntStream.range(0, attempts)
                    .<Callable<Integer>>mapToObj(i -> () -> {
                        // Every thread reaches quota enforcement at the same moment.
                        barrier.await(10, TimeUnit.SECONDS);
                        return coach(user).getResponse().getStatus();
                    })
                    .toList();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : pool.invokeAll(tasks, 60, TimeUnit.SECONDS)) {
                statuses.add(future.get());
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Phase 14 item 8 - two concurrent requests never exceed the quota of one")
    void concurrentQuotaEnforcementAllowsAtMostTheConfiguredLimit() throws Exception {
        Session user = register("quota-race-");
        fake().use(Mode.SUCCESS_WITH_TOKENS);

        List<Integer> statuses = race(user, 2);

        assertThat(statuses)
                .as("exactly one request may pass the quota, order-independent")
                .containsExactlyInAnyOrder(200, 429);
        assertThat(fake().coachCalls())
                .as("at most one provider invocation for a quota of one").isEqualTo(1);
        assertThat(minuteCounter(user.id(), "weekly_coach"))
                .as("the atomic reservation never double-counts").isEqualTo(1);
        assertThat(dayCounter(user.id(), "weekly_coach")).isEqualTo(1);
        assertThat(usageCount(user.id())).as("one success row plus one rejection row").isEqualTo(2);
    }

    @Test
    @DisplayName("Phase 14 item 8 - the quota counter is never negative or duplicated by a race")
    void concurrentQuotaCountersStayConsistent() throws Exception {
        Session user = register("quota-race-counters-");
        fake().use(Mode.SUCCESS_WITH_TOKENS);

        List<Integer> statuses = race(user, 4);

        assertThat(statuses.stream().filter(s -> s == 200).count())
                .as("no more successes than the configured quota").isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 429).count()).isEqualTo(3);
        assertThat(fake().coachCalls()).isEqualTo(1);

        Integer negative = jdbc.queryForObject(
                "select count(*) from ai_quota_counters where user_id=CAST(? as uuid)"
                        + " and feature='weekly_coach' and request_count < 0",
                Integer.class, user.id());
        assertThat(negative).as("no negative counter rows").isZero();
        assertThat(minuteCounter(user.id(), "weekly_coach")).isEqualTo(1);
    }
}

