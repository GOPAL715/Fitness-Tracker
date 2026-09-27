package com.fittrack.acceptance;

import com.fittrack.acceptance.support.AbstractAcceptanceTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Phase 3: exercise library acceptance tests.
 *
 * <p>The exercise catalog is global reference data, so these prove two things: it is readable and
 * correctly shaped for every authenticated user, and it cannot be written by one. The shape matters
 * as much as the status code, because {@code secondary_muscles} is stored as CSV text and the domain
 * contract declares an array that the UI iterates directly.
 */
@SpringBootTest(classes = com.fittrack.api.FitTrackApplication.class)
@AutoConfigureMockMvc
class ExerciseLibraryAcceptanceTest extends AbstractAcceptanceTest {

    /** A catalog row, inserted directly because the catalog is not user-writable. */
    private UUID catalogExercise(String name, String secondaryMuscles) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into exercises (id,name,description,muscle_group,secondary_muscles,equipment,difficulty,instructions,is_compound) "
                        + "values (?,?,?,?,?,?,?,?,?)",
                id, name, "A " + name, "Chest", secondaryMuscles, "Barbell", "Intermediate", "How to do it", true);
        return id;
    }

    /** The catalog row with this name, as app-data returns it. */
    private JsonNode findExercise(JsonNode exercises, String name) {
        for (JsonNode exercise : exercises) {
            if (name.equals(exercise.path("name").asText())) return exercise;
        }
        throw new AssertionError("expected " + name + " in the catalog");
    }

    /* ---------- Shape of the catalog in app-data ---------- */

    @Test
    @DisplayName("app-data returns exercises with secondary_muscles as an array")
    void appDataReturnsSecondaryMusclesAsArray() throws Exception {
        catalogExercise("AppData Curl", "Biceps,Triceps");
        Session session = register("ex-appdata-");

        JsonNode exercises = json(getAs(session, "/api/v1/app-data")).path("exercises");
        assertThat(exercises.isArray()).isTrue();

        JsonNode match = findExercise(exercises, "AppData Curl");
        // An array, not the raw "Biceps,Triceps" string the column stores.
        assertThat(match.path("secondary_muscles").isArray()).isTrue();
        assertThat(match.path("secondary_muscles")).hasSize(2);
        assertThat(match.path("secondary_muscles").get(0).asText()).isEqualTo("Biceps");
        assertThat(match.path("secondary_muscles").get(1).asText()).isEqualTo("Triceps");
    }

    @Test
    @DisplayName("a missing secondary_muscles value becomes an empty array, not null")
    void nullSecondaryMusclesBecomesAnEmptyArray() throws Exception {
        catalogExercise("No Secondaries", null);
        Session session = register("ex-null-");

        JsonNode match = findExercise(json(getAs(session, "/api/v1/app-data")).path("exercises"), "No Secondaries");
        assertThat(match.path("secondary_muscles").isArray()).isTrue();
        assertThat(match.path("secondary_muscles")).isEmpty();
    }

    @Test
    @DisplayName("every returned exercise exposes secondary_muscles as an array")
    void everyExerciseExposesAnArray() throws Exception {
        catalogExercise("Shape One", "Biceps");
        catalogExercise("Shape Two", "Triceps,Deltoids");
        catalogExercise("Shape Three", null);
        Session session = register("ex-shape-");

        JsonNode exercises = json(getAs(session, "/api/v1/app-data")).path("exercises");
        assertThat(exercises).isNotEmpty();
        for (JsonNode exercise : exercises) {
            assertThat(exercise.path("secondary_muscles").isArray())
                    .as("secondary_muscles for %s", exercise.path("name").asText()).isTrue();
        }
    }
    /* ---------- The detail endpoint must agree with app-data ---------- */

    @Test
    @DisplayName("exercise detail returns secondary_muscles as an array, matching app-data")
    void detailReturnsSecondaryMusclesAsArray() throws Exception {
        UUID id = catalogExercise("Detail Shape", "Biceps,Triceps");
        Session session = register("ex-detail-shape-");

        JsonNode detail = json(getAs(session, "/api/v1/exercises/" + id));

        // The inconsistency this closes: /app-data returned an array while this returned CSV text.
        assertThat(detail.path("secondary_muscles").isArray()).isTrue();
        assertThat(detail.path("secondary_muscles")).hasSize(2);
        assertThat(detail.path("secondary_muscles").get(0).asText()).isEqualTo("Biceps");
        assertThat(detail.path("secondary_muscles").get(1).asText()).isEqualTo("Triceps");
        assertThat(detail.path("secondary_muscles").isTextual())
                .as("must not be the raw CSV string").isFalse();
    }

    @Test
    @DisplayName("exercise list returns secondary_muscles as an array too")
    void listReturnsSecondaryMusclesAsArray() throws Exception {
        catalogExercise("List Shape", "Biceps,Triceps");
        Session session = register("ex-list-shape-");

        JsonNode exercises = json(getAs(session, "/api/v1/exercises"));
        JsonNode match = findExercise(exercises, "List Shape");

        assertThat(match.path("secondary_muscles").isArray()).isTrue();
        assertThat(match.path("secondary_muscles")).hasSize(2);
    }

    @Test
    @DisplayName("exercise detail returns an empty array for a null value")
    void detailReturnsEmptyArrayForNull() throws Exception {
        UUID id = catalogExercise("Detail Null", null);
        Session session = register("ex-detail-null-");

        JsonNode detail = json(getAs(session, "/api/v1/exercises/" + id));
        assertThat(detail.path("secondary_muscles").isArray()).isTrue();
        assertThat(detail.path("secondary_muscles")).isEmpty();
    }

    @Test
    @DisplayName("exercise detail returns an empty array for an empty stored string")
    void detailReturnsEmptyArrayForEmptyString() throws Exception {
        UUID id = catalogExercise("Detail Empty", "");
        Session session = register("ex-detail-empty-");

        JsonNode detail = json(getAs(session, "/api/v1/exercises/" + id));
        assertThat(detail.path("secondary_muscles").isArray()).isTrue();
        assertThat(detail.path("secondary_muscles")).isEmpty();
    }

    @Test
    @DisplayName("exercise detail trims whitespace and drops empty CSV entries")
    void detailTrimsAndDropsEmptyEntries() throws Exception {
        // A stray or trailing comma must not surface as an empty badge in the UI.
        UUID id = catalogExercise("Detail Messy", " Biceps , , Triceps ,,");
        Session session = register("ex-detail-messy-");

        JsonNode detail = json(getAs(session, "/api/v1/exercises/" + id));
        assertThat(detail.path("secondary_muscles")).hasSize(2);
        assertThat(detail.path("secondary_muscles").get(0).asText()).isEqualTo("Biceps");
        assertThat(detail.path("secondary_muscles").get(1).asText()).isEqualTo("Triceps");
    }

    @Test
    @DisplayName("the detail and app-data reads agree for the same exercise")
    void detailAndAppDataAgree() throws Exception {
        UUID id = catalogExercise("Agreement Row", "Biceps,Triceps");
        Session session = register("ex-agree-");

        JsonNode detail = json(getAs(session, "/api/v1/exercises/" + id));
        JsonNode viaAppData = findExercise(json(getAs(session, "/api/v1/app-data")).path("exercises"), "Agreement Row");

        assertThat(detail.path("secondary_muscles")).isEqualTo(viaAppData.path("secondary_muscles"));
    }

    @Test
    @DisplayName("normalising a list column leaves the other response fields untouched")
    void otherDetailFieldsAreUnchanged() throws Exception {
        UUID id = catalogExercise("Field Check", "Biceps");
        Session session = register("ex-fields-");

        JsonNode detail = json(getAs(session, "/api/v1/exercises/" + id));
        assertThat(detail.path("id").asText()).isEqualTo(id.toString());
        assertThat(detail.path("name").asText()).isEqualTo("Field Check");
        assertThat(detail.path("description").asText()).isEqualTo("A Field Check");
        assertThat(detail.path("muscle_group").asText()).isEqualTo("Chest");
        assertThat(detail.path("equipment").asText()).isEqualTo("Barbell");
        assertThat(detail.path("difficulty").asText()).isEqualTo("Intermediate");
        assertThat(detail.path("instructions").asText()).isEqualTo("How to do it");
        assertThat(detail.path("is_compound").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("the stored column is still CSV, so the database format is unchanged")
    void storedValueRemainsCsv() throws Exception {
        UUID id = catalogExercise("Stored Check", "Biceps,Triceps");
        Session session = register("ex-stored-");

        json(getAs(session, "/api/v1/exercises/" + id));

        assertThat(jdbc.queryForObject("select secondary_muscles from exercises where id = ?", String.class, id))
                .as("normalising is a read concern only").isEqualTo("Biceps,Triceps");
    }

    /* ---------- Read paths ---------- */

    @Test
    @DisplayName("the catalog is readable by any authenticated user")
    void catalogIsReadableByAnyAuthenticatedUser() throws Exception {
        catalogExercise("Readable Lift", "Biceps");
        Session a = register("ex-read-a-");
        Session b = register("ex-read-b-");

        assertStatus(getAs(a, "/api/v1/exercises"), 200);
        assertStatus(getAs(b, "/api/v1/exercises"), 200);
    }

    @Test
    @DisplayName("exercise detail returns the requested exercise")
    void exerciseDetailReturnsTheExercise() throws Exception {
        UUID id = catalogExercise("Detail Row", "Biceps,Triceps");
        Session session = register("ex-detail-");

        JsonNode detail = json(getAs(session, "/api/v1/exercises/" + id));
        assertThat(detail.path("id").asText()).isEqualTo(id.toString());
        assertThat(detail.path("name").asText()).isEqualTo("Detail Row");
        assertThat(detail.path("muscle_group").asText()).isEqualTo("Chest");
        assertThat(detail.path("is_compound").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("a missing exercise returns a structured 404")
    void missingExerciseReturns404() throws Exception {
        Session session = register("ex-missing-");
        assertStructuredError(getAs(session, "/api/v1/exercises/" + UUID.randomUUID()), 404, "Not Found");
    }

    @Test
    @DisplayName("the catalog requires authentication")
    void catalogRequiresAuthentication() throws Exception {
        assertUnauthenticated(get("/api/v1/exercises"));
        assertUnauthenticated(get("/api/v1/app-data"));
    }

    /* ---------- The catalog must stay read-only ---------- */

    @Test
    @DisplayName("an authenticated user cannot create a catalog exercise")
    void catalogCannotBeCreated() throws Exception {
        Session session = register("ex-nocreate-");
        MvcResult result = mvc.perform(post("/api/v1/exercises")
                .header("Authorization", "Bearer " + session.access())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Sneaky\"}")).andReturn();

        assertStructuredError(result, 403, "Forbidden");
        assertThat(jdbc.queryForObject("select count(*) from exercises where name = 'Sneaky'", Integer.class))
                .as("nothing was written").isZero();
    }

    @Test
    @DisplayName("an authenticated user cannot update a catalog exercise")
    void catalogCannotBeUpdated() throws Exception {
        UUID id = catalogExercise("Untouched Lift", "Biceps");
        Session session = register("ex-noupdate-");

        MvcResult result = mvc.perform(put("/api/v1/exercises/" + id)
                .header("Authorization", "Bearer " + session.access())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Renamed\"}")).andReturn();

        assertStructuredError(result, 403, "Forbidden");
        assertThat(jdbc.queryForObject("select name from exercises where id = ?", String.class, id))
                .as("the catalog row is unchanged").isEqualTo("Untouched Lift");
    }

    @Test
    @DisplayName("an authenticated user cannot delete a catalog exercise")
    void catalogCannotBeDeleted() throws Exception {
        UUID id = catalogExercise("Survivor Lift", "Biceps");
        Session session = register("ex-nodelete-");

        MvcResult result = mvc.perform(delete("/api/v1/exercises/" + id)
                .header("Authorization", "Bearer " + session.access())).andReturn();

        assertStructuredError(result, 403, "Forbidden");
        assertThat(jdbc.queryForObject("select count(*) from exercises where id = ?", Integer.class, id))
                .as("the catalog row still exists").isEqualTo(1);
    }

    @Test
    @DisplayName("one user's rejected write cannot alter the catalog another user reads")
    void rejectedWritesDoNotAffectTheSharedCatalog() throws Exception {
        UUID id = catalogExercise("Shared Lift", "Biceps");
        Session a = register("ex-shared-a-");
        Session b = register("ex-shared-b-");

        mvc.perform(put("/api/v1/exercises/" + id)
                .header("Authorization", "Bearer " + a.access())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Hijacked\"}"));

        // B reads the same global row and still sees the original value.
        assertThat(json(getAs(b, "/api/v1/exercises/" + id)).path("name").asText()).isEqualTo("Shared Lift");
    }
}
