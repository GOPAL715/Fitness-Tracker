package com.fittrack.acceptance;

import com.fittrack.acceptance.support.AbstractAcceptanceTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Phase 5: goals and body-metric acceptance tests.
 *
 * <p>Goals previously accepted any value at all, and a body metric could not be written through the
 * UI path at all. These pin the contract the screens depend on: a goal is a real, projectable record
 * with a start date, and a body measurement is one row per user per day.
 */
@SpringBootTest(classes = com.fittrack.api.FitTrackApplication.class)
@AutoConfigureMockMvc
class GoalsProgressAcceptanceTest extends AbstractAcceptanceTest {

    private MvcResult postAs(Session s, String path, String body) throws Exception {
        return mvc.perform(post(path).header("Authorization", "Bearer " + s.access())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private MvcResult putAs(Session s, String path, String body) throws Exception {
        return mvc.perform(put(path).header("Authorization", "Bearer " + s.access())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    /** A goal the server accepts, so a test can vary exactly one field. */
    private String goalJson(String title, String extra) {
        return "{\"title\":\"" + title + "\",\"goal_type\":\"Build Strength\",\"start_value\":100,"
                + "\"target_value\":200,\"current_value\":100,\"unit\":\"lbs\",\"status\":\"active\""
                + (extra == null ? "" : "," + extra) + "}";
    }

    private String createGoal(Session s, String title) throws Exception {
        MvcResult r = postAs(s, "/api/v1/goals", goalJson(title, null));
        assertStatus(r, 200);
        return json(r).path("id").asText();
    }

    /* ---------- M3: the server owns start_date ---------- */

    @Test
    @DisplayName("a goal created without a start_date is stamped with the server's current date")
    void goalWithoutStartDateGetsServerDate() throws Exception {
        Session s = register("p5-startdate-");
        MvcResult r = postAs(s, "/api/v1/goals", goalJson("Server Dated", null));
        assertStatus(r, 200);

        // UTC today, the same clock the analytics endpoints use.
        assertThat(jdbc.queryForObject("select start_date from goals where id=CAST(? as uuid)",
                LocalDate.class, json(r).path("id").asText())).isEqualTo(LocalDate.now(java.time.ZoneOffset.UTC));
    }

    @Test
    @DisplayName("an explicitly supplied start_date is preserved, not overridden")
    void explicitStartDateIsPreserved() throws Exception {
        Session s = register("p5-explicit-");
        MvcResult r = postAs(s, "/api/v1/goals", goalJson("Explicit Dated", "\"start_date\":\"2026-01-15\""));
        assertStatus(r, 200);

        assertThat(jdbc.queryForObject("select start_date from goals where id=CAST(? as uuid)",
                LocalDate.class, json(r).path("id").asText())).isEqualTo(LocalDate.parse("2026-01-15"));
    }

    @Test
    @DisplayName("app-data returns the server-assigned start_date, so the projection is calculable")
    void appDataReturnsStartDate() throws Exception {
        Session s = register("p5-appdata-");
        String id = json(postAs(s, "/api/v1/goals", goalJson("Projectable", null))).path("id").asText();

        JsonNode goals = json(getAs(s, "/api/v1/app-data")).path("goals");
        JsonNode match = null;
        for (JsonNode g : goals) {
            if (id.equals(g.path("id").asText())) match = g;
        }
        assertThat(match).as("the goal is returned by app-data").isNotNull();
        // The client derives the estimate from this, so a null here silently disables it.
        assertThat(match.path("start_date").isNull()).isFalse();
        assertThat(match.path("start_date").asText()).isNotBlank();
    }
    /* ---------- M2: goal validation ---------- */

    @Test
    @DisplayName("a goal without a title is rejected")
    void goalWithoutTitleIsRejected() throws Exception {
        Session s = register("p5-notitle-");
        assertStructuredError(postAs(s, "/api/v1/goals",
                "{\"start_value\":0,\"target_value\":10,\"status\":\"active\"}"), 400, "Bad Request");
    }

    @Test
    @DisplayName("a blank title is rejected")
    void blankTitleIsRejected() throws Exception {
        Session s = register("p5-blanktitle-");
        assertStructuredError(postAs(s, "/api/v1/goals", goalJson("   ", null)), 400, "Bad Request");
    }

    @Test
    @DisplayName("a status outside the two the product uses is rejected, and both real ones pass")
    void unknownStatusIsRejected() throws Exception {
        Session s = register("p5-badstatus-");
        assertStructuredError(postAs(s, "/api/v1/goals", goalJson("Bad Status", "\"status\":\"banana\"")),
                400, "Bad Request");
        assertStatus(postAs(s, "/api/v1/goals", goalJson("Active", "\"status\":\"active\"")), 200);
        assertStatus(postAs(s, "/api/v1/goals", goalJson("Achieved", "\"status\":\"achieved\"")), 200);
    }

    @Test
    @DisplayName("a start and target that are equal are rejected")
    void equalStartAndTargetIsRejected() throws Exception {
        Session s = register("p5-equal-");
        assertStructuredError(postAs(s, "/api/v1/goals",
                "{\"title\":\"Same\",\"start_value\":100,\"target_value\":100,\"status\":\"active\"}"),
                400, "Bad Request");
    }

    @Test
    @DisplayName("a goal with no start or target is rejected")
    void goalWithoutValuesIsRejected() throws Exception {
        Session s = register("p5-novalues-");
        assertStructuredError(postAs(s, "/api/v1/goals", "{\"title\":\"No Values\",\"status\":\"active\"}"),
                400, "Bad Request");
    }

    @Test
    @DisplayName("a start date after the target date is rejected")
    void reversedDatesAreRejected() throws Exception {
        Session s = register("p5-reversed-");
        assertStructuredError(postAs(s, "/api/v1/goals",
                goalJson("Reversed", "\"start_date\":\"2026-06-01\",\"target_date\":\"2026-01-01\"")),
                400, "Bad Request");
    }

    @Test
    @DisplayName("a past target_date is allowed, because an overdue goal is legitimate")
    void pastTargetDateIsAllowed() throws Exception {
        Session s = register("p5-overdue-");
        assertStatus(postAs(s, "/api/v1/goals", goalJson("Overdue", "\"target_date\":\"2020-01-01\"")), 200);
    }

    @Test
    @DisplayName("validation also applies to an update")
    void updateIsValidated() throws Exception {
        Session s = register("p5-updatevalid-");
        String id = createGoal(s, "Update Validated");

        assertStructuredError(putAs(s, "/api/v1/goals/" + id, "{\"status\":\"banana\"}"), 400, "Bad Request");
        assertStatus(putAs(s, "/api/v1/goals/" + id, "{\"status\":\"achieved\"}"), 200);
    }

    /* ---------- Goal CRUD and ownership ---------- */

    @Test
    @DisplayName("a goal can be created, read, updated and deleted by its owner")
    void goalCrudForOwner() throws Exception {
        Session s = register("p5-crud-");
        String id = createGoal(s, "CRUD Goal");

        assertStatus(getAs(s, "/api/v1/goals/" + id), 200);
        assertStatus(putAs(s, "/api/v1/goals/" + id, "{\"current_value\":150}"), 200);
        assertThat(json(getAs(s, "/api/v1/goals/" + id)).path("current_value").asInt()).isEqualTo(150);
        assertStatus(mvc.perform(delete("/api/v1/goals/" + id)
                .header("Authorization", "Bearer " + s.access())).andReturn(), 200);
        assertStatus(getAs(s, "/api/v1/goals/" + id), 404);
    }

    @Test
    @DisplayName("another user cannot read, update or delete a goal")
    void goalIsOwnerScoped() throws Exception {
        Session owner = register("p5-owner-");
        Session other = register("p5-other-");
        String id = createGoal(owner, "Private Goal");

        assertStatus(getAs(other, "/api/v1/goals/" + id), 404);
        assertStatus(putAs(other, "/api/v1/goals/" + id, "{\"title\":\"Hijacked\"}"), 404);
        assertStatus(mvc.perform(delete("/api/v1/goals/" + id)
                .header("Authorization", "Bearer " + other.access())).andReturn(), 404);

        assertThat(json(getAs(owner, "/api/v1/goals/" + id)).path("title").asText()).isEqualTo("Private Goal");
    }

    @Test
    @DisplayName("a forged user_id cannot reassign a goal")
    void forgedUserIdCannotChangeOwner() throws Exception {
        Session owner = register("p5-forged-");
        Session victim = register("p5-victim-");
        String id = createGoal(owner, "Forged Goal");

        assertStructuredError(putAs(owner, "/api/v1/goals/" + id, "{\"user_id\":\"" + victim.id() + "\"}"),
                400, "Bad Request");
        assertThat(jdbc.queryForObject("select user_id from goals where id=CAST(? as uuid)", UUID.class, id))
                .isEqualTo(UUID.fromString(owner.id()));
    }

    @Test
    @DisplayName("the goal list only returns the caller's own goals")
    void goalListIsOwnerScoped() throws Exception {
        Session a = register("p5-list-a-");
        Session b = register("p5-list-b-");
        createGoal(a, "A Only Goal");
        createGoal(b, "B Only Goal");

        assertThat(json(getAs(a, "/api/v1/goals")).toString()).contains("A Only Goal").doesNotContain("B Only Goal");
    }
    /* ---------- M1: body metrics ---------- */

    @Test
    @DisplayName("a body metric is created and read back")
    void bodyMetricIsCreated() throws Exception {
        Session s = register("p5-body-create-");
        MvcResult r = postAs(s, "/api/v1/body-metrics",
                "{\"metric_date\":\"2026-03-01\",\"weight_lb\":180.5,\"body_fat_pct\":17.5}");
        assertStatus(r, 200);

        JsonNode row = json(r);
        assertThat(row.path("weight_lb").asDouble()).isEqualTo(180.5);
        assertThat(row.path("id").asText()).isNotBlank();
        // Ownership comes from the token, never from the body.
        assertThat(row.path("user_id").asText()).isEqualTo(s.id());
    }

    @Test
    @DisplayName("a second measurement for the same day updates the row instead of duplicating it")
    void bodyMetricSameDayIsUpserted() throws Exception {
        Session s = register("p5-body-upsert-");
        String first = json(postAs(s, "/api/v1/body-metrics",
                "{\"metric_date\":\"2026-03-02\",\"weight_lb\":180.0}")).path("id").asText();
        String second = json(postAs(s, "/api/v1/body-metrics",
                "{\"metric_date\":\"2026-03-02\",\"weight_lb\":178.5,\"body_fat_pct\":16.0}")).path("id").asText();

        // Same row, updated values, still one row for that day.
        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("select count(*) from body_metrics where user_id=CAST(? as uuid)",
                Integer.class, s.id())).isEqualTo(1);

        JsonNode stored = json(getAs(s, "/api/v1/body-metrics/" + first));
        assertThat(stored.path("weight_lb").asDouble()).isEqualTo(178.5);
        assertThat(stored.path("body_fat_pct").asDouble()).isEqualTo(16.0);
    }

    @Test
    @DisplayName("a different day creates a separate row")
    void bodyMetricDifferentDayCreatesAnotherRow() throws Exception {
        Session s = register("p5-body-days-");
        postAs(s, "/api/v1/body-metrics", "{\"metric_date\":\"2026-03-03\",\"weight_lb\":180.0}");
        postAs(s, "/api/v1/body-metrics", "{\"metric_date\":\"2026-03-04\",\"weight_lb\":179.0}");

        assertThat(jdbc.queryForObject("select count(*) from body_metrics where user_id=CAST(? as uuid)",
                Integer.class, s.id())).isEqualTo(2);
    }

    @Test
    @DisplayName("a body metric is owner-scoped and cannot be forged onto another account")
    void bodyMetricIsOwnerScoped() throws Exception {
        Session owner = register("p5-body-owner-");
        Session other = register("p5-body-other-");
        String id = json(postAs(owner, "/api/v1/body-metrics",
                "{\"metric_date\":\"2026-03-05\",\"weight_lb\":180.0}")).path("id").asText();

        assertStatus(getAs(other, "/api/v1/body-metrics/" + id), 404);
        assertStatus(putAs(other, "/api/v1/body-metrics/" + id, "{\"weight_lb\":100.0}"), 404);
        assertStructuredError(postAs(owner, "/api/v1/body-metrics",
                "{\"metric_date\":\"2026-03-06\",\"weight_lb\":180.0,\"user_id\":\"" + other.id() + "\"}"),
                400, "Bad Request");

        assertThat(json(getAs(owner, "/api/v1/body-metrics/" + id)).path("weight_lb").asDouble()).isEqualTo(180.0);
    }

    @Test
    @DisplayName("two users can each have a measurement on the same day")
    void bodyMetricUpsertIsOwnerScoped() throws Exception {
        Session a = register("p5-upsert-a-");
        Session b = register("p5-upsert-b-");
        postAs(a, "/api/v1/body-metrics", "{\"metric_date\":\"2026-03-07\",\"weight_lb\":200.0}");
        postAs(b, "/api/v1/body-metrics", "{\"metric_date\":\"2026-03-07\",\"weight_lb\":150.0}");

        assertThat(jdbc.queryForObject("select count(*) from body_metrics where user_id=CAST(? as uuid)",
                Integer.class, a.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from body_metrics where user_id=CAST(? as uuid)",
                Integer.class, b.id())).isEqualTo(1);
    }
    /* ---------- M4: body metric validation ---------- */

    @Test
    @DisplayName("a malformed metric_date is a 400, not a 500")
    void malformedDateIsRejected() throws Exception {
        Session s = register("p5-baddate-");
        assertStructuredError(postAs(s, "/api/v1/body-metrics",
                "{\"metric_date\":\"not-a-date\",\"weight_lb\":180}"), 400, "Bad Request");
    }

    @Test
    @DisplayName("a missing metric_date is rejected")
    void missingDateIsRejected() throws Exception {
        Session s = register("p5-nodate-");
        assertStructuredError(postAs(s, "/api/v1/body-metrics", "{\"weight_lb\":180}"), 400, "Bad Request");
    }

    @Test
    @DisplayName("a non-positive weight is a 400, not a 500")
    void nonPositiveWeightIsRejected() throws Exception {
        Session s = register("p5-badweight-");
        assertStructuredError(postAs(s, "/api/v1/body-metrics",
                "{\"metric_date\":\"2026-03-08\",\"weight_lb\":0}"), 400, "Bad Request");
        assertStructuredError(postAs(s, "/api/v1/body-metrics",
                "{\"metric_date\":\"2026-03-08\",\"weight_lb\":-5}"), 400, "Bad Request");
    }

    @Test
    @DisplayName("a valid weight with null optionals is accepted")
    void validBodyMetricIsAccepted() throws Exception {
        Session s = register("p5-validbody-");
        assertStatus(postAs(s, "/api/v1/body-metrics",
                "{\"metric_date\":\"2026-03-09\",\"weight_lb\":0.1,\"body_fat_pct\":null,\"waist_in\":null}"), 200);
    }

    @Test
    @DisplayName("personal records stay readable and owner-scoped, and are never auto-created")
    void personalRecordsRemainReadOnly() throws Exception {
        Session owner = register("p5-pr-");
        Session other = register("p5-pr-other-");
        String id = json(postAs(owner, "/api/v1/personal-records",
                "{\"exercise\":\"Bench Press\",\"record_value\":225,\"unit\":\"lbs\",\"achieved_date\":\"2026-03-08\"}")).path("id").asText();

        assertStatus(getAs(owner, "/api/v1/personal-records/" + id), 200);
        assertStatus(getAs(other, "/api/v1/personal-records/" + id), 404);
        // Phase 5 must not start inventing records from completed sessions.
        assertThat(json(getAs(owner, "/api/v1/app-data")).path("records")).hasSize(1);
    }
}
