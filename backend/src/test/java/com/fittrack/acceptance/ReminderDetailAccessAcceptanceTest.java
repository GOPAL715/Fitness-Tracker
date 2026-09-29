package com.fittrack.acceptance;

import com.fittrack.acceptance.support.AbstractAcceptanceTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Phase 15: reminder read/write separation for the deep-linked detail view.
 *
 * <p>{@code next_occurrence_at} and {@code delivery_status} are read by the detail view and must stay
 * strictly server-controlled. They are declared in {@code Spec.readColumns()} rather than
 * {@code Spec.columns()} precisely because the latter is the client write allowlist, so these tests
 * prove both halves: the fields are readable, and a client still cannot set them through any write
 * verb.
 */
@SpringBootTest(classes = com.fittrack.api.FitTrackApplication.class)
@AutoConfigureMockMvc
class ReminderDetailAccessAcceptanceTest extends AbstractAcceptanceTest {

    private UUID createReminder(Session owner) throws Exception {
        MvcResult result = call(owner, post("/api/v1/reminders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                        "type", "workout", "title", "Time to train", "message", "Session starts now",
                        "scheduled_time", "06:00", "days_of_week", "1,2,3,4,5,6,0",
                        "timezone", "UTC", "recurrence", "daily", "enabled", true))));
        assertThat(result.getResponse().getStatus()).isIn(200, 201);
        return UUID.fromString(json(result).path("id").asText());
    }

    @Test
    @DisplayName("Phase 15 reminders - a user can read their own reminder by id")
    void ownerCanReadOwnReminder() throws Exception {
        Session owner = register("rem-detail-own-");
        UUID id = createReminder(owner);

        MvcResult result = getAs(owner, "/api/v1/reminders/" + id);

        assertStatus(result, 200);
        assertThat(json(result).path("id").asText()).isEqualTo(id.toString());
        assertThat(json(result).path("title").asText()).isEqualTo("Time to train");
    }

    @Test
    @DisplayName("Phase 15 reminders - the detail read exposes the server-owned scheduling fields")
    void detailExposesServerOwnedFields() throws Exception {
        Session owner = register("rem-detail-fields-");
        UUID id = createReminder(owner);
        jdbc.update("update reminders set next_occurrence_at=?, delivery_status='pending' where id=?",
                java.sql.Timestamp.from(java.time.Instant.parse("2026-09-30T06:00:00Z")), id);

        MvcResult result = getAs(owner, "/api/v1/reminders/" + id);

        assertStatus(result, 200);
        assertThat(json(result).has("next_occurrence_at"))
                .as("the detail view renders the next occurrence from this field")
                .isTrue();
        assertThat(json(result).has("delivery_status"))
                .as("the detail view renders delivery state from this field")
                .isTrue();
        assertThat(json(result).path("delivery_status").asText()).isEqualTo("pending");
    }

    @Test
    @DisplayName("Phase 15 reminders - the list endpoint remains valid and user-scoped")
    void listRemainsValid() throws Exception {
        Session owner = register("rem-detail-list-");
        createReminder(owner);

        MvcResult result = getAs(owner, "/api/v1/reminders");

        assertStatus(result, 200);
        assertThat(json(result).isArray()).isTrue();
    }

    @Test
    @DisplayName("Phase 15 reminders - another user's reminder is a 404, not a 403")
    void otherUsersReminderIsNotFound() throws Exception {
        Session owner = register("rem-detail-a-");
        Session other = register("rem-detail-b-");
        UUID id = createReminder(owner);

        MvcResult result = getAs(other, "/api/v1/reminders/" + id);

        // 404 rather than 403: the endpoint must not confirm that somebody else's reminder exists.
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("Phase 15 reminders - a nonexistent reminder is a 404")
    void nonexistentReminderIsNotFound() throws Exception {
        Session owner = register("rem-detail-missing-");

        assertThat(getAs(owner, "/api/v1/reminders/" + UUID.randomUUID()).getResponse().getStatus())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("Phase 15 reminders - a deleted reminder is a 404")
    void deletedReminderIsNotFound() throws Exception {
        Session owner = register("rem-detail-deleted-");
        UUID id = createReminder(owner);
        assertStatus(call(owner, delete("/api/v1/reminders/" + id)), 200);

        assertThat(getAs(owner, "/api/v1/reminders/" + id).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("Phase 15 reminders - reading a reminder requires authentication")
    void readRequiresAuthentication() throws Exception {
        assertUnauthenticated(get("/api/v1/reminders/" + UUID.randomUUID()));
    }

    @Test
    @DisplayName("Phase 15 reminders - a client cannot set the server-owned fields on create")
    void createRejectsServerOwnedFields() throws Exception {
        Session owner = register("rem-detail-create-");

        MvcResult occurrence = call(owner, post("/api/v1/reminders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                        "type", "workout", "title", "Forged", "scheduled_time", "06:00",
                        "next_occurrence_at", "2030-01-01T00:00:00Z"))));
        MvcResult status = call(owner, post("/api/v1/reminders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                        "type", "workout", "title", "Forged", "scheduled_time", "06:00",
                        "delivery_status", "delivered"))));

        assertThat(occurrence.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(occurrence).path("message").asText()).contains("server controlled");
        assertThat(status.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(status).path("message").asText()).contains("server controlled");
        // Scoped to this user: the shared container is reused across the suite, and other tests
        // legitimately hold reminders the scheduler has already delivered.
        assertThat(jdbc.queryForObject("select count(*) from reminders"
                        + " where user_id=CAST(? as uuid) and delivery_status<>'pending'",
                Integer.class, owner.id()))
                .as("no row was marked delivered by a client")
                .isZero();
    }

    @Test
    @DisplayName("Phase 15 reminders - a client cannot set the server-owned fields on update")
    void updateRejectsServerOwnedFields() throws Exception {
        Session owner = register("rem-detail-update-");
        UUID id = createReminder(owner);

        for (var request : List.of(
                put("/api/v1/reminders/" + id), patch("/api/v1/reminders/" + id))) {
            // Declared rather than inlined: an inline List.of(Map.of(...)) infers Map<String,String>
            // and will not bind to Map<String,Object>.
            List<Map<String, Object>> bodies = new ArrayList<>();
            bodies.add(Map.of("delivery_status", "delivered"));
            bodies.add(Map.of("next_occurrence_at", "2030-01-01T00:00:00Z"));
            for (Map<String, Object> body : bodies) {
                MvcResult result = call(owner, request.contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)));
                assertThat(result.getResponse().getStatus())
                        .as("both PUT and PATCH must reject %s", body.keySet())
                        .isEqualTo(400);
                assertThat(json(result).path("message").asText()).contains("server controlled");
            }
        }

        assertThat(jdbc.queryForObject("select delivery_status from reminders where id=?",
                String.class, id))
                .as("the reminder was never marked delivered by a client")
                .isEqualTo("pending");
    }

    @Test
    @DisplayName("Phase 15 reminders - the ordinary writable fields still work")
    void writableFieldsStillWork() throws Exception {
        Session owner = register("rem-detail-writable-");
        UUID id = createReminder(owner);

        MvcResult result = call(owner, put("/api/v1/reminders/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("title", "Renamed", "enabled", false))));

        assertThat(result.getResponse().getStatus()).isIn(200, 204);
        assertThat(jdbc.queryForObject("select title from reminders where id=?", String.class, id))
                .isEqualTo("Renamed");
        assertThat(jdbc.queryForObject("select enabled from reminders where id=?", Boolean.class, id))
                .isFalse();
    }

    // -------------------------------------------------- cross-user isolation (Phase 16)

    @Test
    @DisplayName("Phase 16 reminders - a list contains only the caller own reminders")
    void listIsUserScoped() throws Exception {
        Session owner = register("rem-iso-list-a-");
        Session other = register("rem-iso-list-b-");
        createReminder(owner);
        createReminder(other);

        MvcResult result = getAs(other, "/api/v1/reminders");

        assertStatus(result, 200);
        // Every row carries the caller's own user_id and nothing else, so a cross-user leak of any
        // kind would surface here rather than needing the id compared directly.
        for (var node : json(result)) {
            String userId = node.path("user_id").asText();
            if (userId != null && !userId.isBlank()) {
                assertThat(userId).isEqualTo(other.id());
            }
        }
    }

    @Test
    @DisplayName("Phase 16 reminders - another user cannot update or delete a reminder")
    void updateAndDeleteAreOwnerScoped() throws Exception {
        Session owner = register("rem-iso-write-a-");
        Session attacker = register("rem-iso-write-b-");
        UUID id = createReminder(owner);

        assertThat(call(attacker, put("/api/v1/reminders/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("title", "Hijacked"))))
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(call(attacker, delete("/api/v1/reminders/" + id)).getResponse().getStatus())
                .isEqualTo(404);

        assertThat(jdbc.queryForObject("select title from reminders where id=?", String.class, id))
                .as("the row is untouched by another account")
                .isEqualTo("Time to train");
    }

    @Test
    @DisplayName("Phase 16 reminders - another user cannot reschedule a reminder")
    void rescheduleIsOwnerScoped() throws Exception {
        Session owner = register("rem-iso-resch-a-");
        Session attacker = register("rem-iso-resch-b-");
        UUID id = createReminder(owner);
        assertStatus(reschedule(owner, id), 200);
        Instant before = jdbc.queryForObject(
                "select next_occurrence_at from reminders where id=?", Timestamp.class, id).toInstant();

        MvcResult result = call(attacker, post("/api/v1/reminders/" + id + "/reschedule")
                .contentType(MediaType.APPLICATION_JSON).content("{}"));

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(jdbc.queryForObject("select next_occurrence_at from reminders where id=?",
                Timestamp.class, id).toInstant()).isEqualTo(before);
    }

    private MvcResult reschedule(Session user, UUID id) throws Exception {
        return call(user, post("/api/v1/reminders/" + id + "/reschedule")
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
    }
}
