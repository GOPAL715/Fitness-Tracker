package com.fittrack.acceptance;

import com.fittrack.acceptance.support.AbstractAcceptanceTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 15 C: idempotency for retried offline composite writes.
 *
 * <p>Proves the same logical offline operation submitted twice produces one aggregate, and that
 * an idempotency key cannot be replayed across users.
 */
@SpringBootTest(classes = com.fittrack.api.FitTrackApplication.class)
@AutoConfigureMockMvc
class OfflineIdempotencyAcceptanceTest extends AbstractAcceptanceTest {

    @Test
    @DisplayName("Phase 15 offline - the same idempotency key creates exactly one aggregate")
    void repeatedOfflineSubmissionCreatesOneAggregate() throws Exception {
        Session user = register("idem-user-");
        UUID exercise = UUID.randomUUID();
        jdbc.update("insert into exercises(id,name) values (?,?)", exercise, "Bench Press");
        String key = "op-offline-" + UUID.randomUUID();
        Map<String, Object> body = sessionBody(exercise, "Offline session");

        MvcResult first = complete(user, key, body);
        MvcResult second = complete(user, key, body);

        JsonNode a = json(first);
        JsonNode b = json(second);
        assertThat(a.path("id").asText()).isEqualTo(b.path("id").asText());
        assertThat(a.path("child_count").asInt()).isEqualTo(b.path("child_count").asInt());
        assertThat(sessionCount(user.id()))
                .as("a retried offline operation must not duplicate the session").isEqualTo(1);
    }

    @Test
    @DisplayName("Phase 15 offline - a request without an idempotency key is unaffected")
    void requestsWithoutAKeyStillCreateDistinctAggregates() throws Exception {
        Session user = register("idem-none-");
        UUID exercise = UUID.randomUUID();
        jdbc.update("insert into exercises(id,name) values (?,?)", exercise, "Squat");
        Map<String, Object> body = sessionBody(exercise, "No key");

        MvcResult first = call(user, post("/api/v1/workout-sessions/complete")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)));
        MvcResult second = call(user, post("/api/v1/workout-sessions/complete")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)));

        assertThat(json(first).path("id").asText()).isNotEqualTo(json(second).path("id").asText());
        assertThat(sessionCount(user.id())).isEqualTo(2);
    }

    @Test
    @DisplayName("Phase 15 offline - an idempotency key is scoped to its owner")
    void idempotencyKeysCannotCrossUsers() throws Exception {
        Session owner = register("idem-owner-");
        Session attacker = register("idem-attacker-");
        UUID exercise = UUID.randomUUID();
        jdbc.update("insert into exercises(id,name) values (?,?)", exercise, "Deadlift");
        String key = "op-shared-" + UUID.randomUUID();

        complete(owner, key, sessionBody(exercise, "Owner session"));
        // Reusing the key as another user must not resolve to the owner's aggregate.
        complete(attacker, key, sessionBody(exercise, "Attacker session"));

        assertThat(sessionCount(attacker.id()))
                .as("a foreign key replay must not resolve to another user's aggregate").isEqualTo(1);
        assertThat(sessionCount(owner.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("Phase 15 offline - a validation failure is not recorded as a successful replay")
    void failedOperationIsNotMemoizedAsSuccess() throws Exception {
        Session user = register("idem-fail-");
        String key = "op-fail-" + UUID.randomUUID();

        MvcResult result = call(user, post("/api/v1/workout-sessions/complete")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"session\":{\"title\":\"Missing fields\"}}"));
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(jdbc.queryForObject("select count(*) from idempotency_keys where user_id=CAST(? as uuid)",
                Integer.class, user.id()))
                .as("a rejected request leaves no replay record").isZero();
    }

    @Test
    @DisplayName("Phase 15 offline - a retried queued submission creates only one logical session")
    void retriedQueuedSubmissionCreatesOneLogicalSession() throws Exception {
        Session user = register("idem-queue-");
        UUID exercise = UUID.randomUUID();
        jdbc.update("insert into exercises(id,name) values (?,?)", exercise, "Row");
        // One queued operation replays with one stable idempotency key, exactly as the client does.
        String idempotencyKey = "op_queue_" + UUID.randomUUID();
        String payload = mapper.writeValueAsString(sessionBody(exercise, "Queued session"));

        // The first attempt fails at the transport layer after the client already queued it.
        MvcResult firstAttempt = call(user, post("/api/v1/workout-sessions/complete")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON).content(payload));
        assertThat(firstAttempt.getResponse().getStatus()).isEqualTo(200);
        // The client retries the identical queued request (network blip) with the same key.
        MvcResult retry = call(user, post("/api/v1/workout-sessions/complete")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON).content(payload));
        MvcResult secondRetry = call(user, post("/api/v1/workout-sessions/complete")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON).content(payload));

        assertThat(json(retry).path("id").asText()).isEqualTo(json(firstAttempt).path("id").asText());
        assertThat(json(secondRetry).path("id").asText()).isEqualTo(json(firstAttempt).path("id").asText());
        assertThat(sessionCount(user.id()))
                .as("a retried offline operation must not duplicate the session").isEqualTo(1);
        // A single idempotency record backs every replay.
        assertThat(jdbc.queryForObject("select count(*) from idempotency_keys where user_id=CAST(? as uuid)",
                Integer.class, user.id())).isEqualTo(1);
    }

    private MvcResult complete(Session user, String key, Map<String, Object> body) throws Exception {
        return call(user, post("/api/v1/workout-sessions/complete")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)));
    }

    private int sessionCount(String userId) {
        return jdbc.queryForObject("select count(*) from workout_sessions where user_id=CAST(? as uuid)",
                Integer.class, userId);
    }

    private Map<String, Object> sessionBody(UUID exercise, String title) {
        return new java.util.LinkedHashMap<>(Map.of(
                "session", Map.of("title", title, "workout_type", "Strength", "duration_minutes", 45,
                        "perceived_effort", 7, "completed", true),
                "exercises", List.of(Map.of("exercise_id", exercise, "order_index", 0,
                        "sets", List.of(Map.of("set_number", 1, "reps", 5, "weight", 60, "completed", true))))));
    }
}
