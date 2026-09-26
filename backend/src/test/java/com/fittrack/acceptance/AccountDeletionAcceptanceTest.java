package com.fittrack.acceptance;

import com.fittrack.acceptance.support.AbstractAcceptanceTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 16: account deletion.
 *
 * <p>Proves deletion removes only the caller's data, revokes their sessions, is idempotent, and
 * cannot be aimed at another account.
 */
@SpringBootTest(classes = com.fittrack.api.FitTrackApplication.class)
@AutoConfigureMockMvc
class AccountDeletionAcceptanceTest extends AbstractAcceptanceTest {

    @Test
    @DisplayName("Phase 16 deletion - deleting the current account removes their owned data")
    void deletionRemovesOwnedData() throws Exception {
        Session user = register("del-");
        UUID workout = UUID.randomUUID();
        jdbc.update("insert into workout_sessions (id, user_id, title, started_at, duration_minutes) "
                        + "values (?,?,?,now(),60)",
                workout, java.util.UUID.fromString(user.id()), "ToBeDeleted");

        MvcResult result = call(user, delete("/api/v1/me"));
        assertThat(result.getResponse().getStatus()).isIn(200, 204);
        assertThat(json(result).path("deleted").asBoolean()).isTrue();

        assertThat(jdbc.queryForObject("select count(*) from workout_sessions where id=?", Integer.class, workout))
                .as("owned rows must be gone").isZero();
        assertThat(jdbc.queryForObject("select count(*) from app_users where id=CAST(? as uuid)",
                Integer.class, java.util.UUID.fromString(user.id()))).isZero();
    }

    @Test
    @DisplayName("Phase 16 deletion - deletion revokes the user's sessions")
    void deletionRevokesSessions() throws Exception {
        Session user = register("del-session-");
        assertThat(user.refresh()).isNotBlank();
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where user_id=CAST(? as uuid)",
                Integer.class, java.util.UUID.fromString(user.id()))).isPositive();

        call(user, delete("/api/v1/me"));

        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where user_id=CAST(? as uuid)",
                Integer.class, java.util.UUID.fromString(user.id())))
                .as("refresh tokens must be revoked").isZero();
    }

    @Test
    @DisplayName("Phase 16 deletion - another user's data is untouched")
    void deletionCannotTargetAnotherUser() throws Exception {
        Session victim = register("del-victim-");
        UUID victimWorkout = UUID.randomUUID();
        jdbc.update("insert into workout_sessions (id, user_id, title, started_at, duration_minutes) "
                        + "values (?,?,?,now(),60)",
                victimWorkout, java.util.UUID.fromString(victim.id()), "VictimData");
        Session attacker = register("del-attacker-");

        // There is no endpoint that accepts a target user id, so this only deletes the caller.
        call(attacker, delete("/api/v1/me"));
        assertThat(jdbc.queryForObject("select count(*) from workout_sessions where id=?", Integer.class, victimWorkout))
                .as("the victim's data must survive another user's deletion").isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from app_users where id=CAST(? as uuid)",
                Integer.class, java.util.UUID.fromString(victim.id()))).isEqualTo(1);
    }

    @Test
    @DisplayName("Phase 16 deletion - deleting an already-deleted account is idempotent")
    void deletionIsIdempotent() throws Exception {
        Session user = register("del-idem-");
        MvcResult first = call(user, delete("/api/v1/me"));
        assertThat(json(first).path("deleted").asBoolean()).isTrue();

        // A second attempt is a safe no-op rather than an error or a crash.
        MvcResult second = mvc.perform(delete("/api/v1/me")
                .header("Authorization", "Bearer " + user.access())).andReturn();
        assertThat(second.getResponse().getStatus()).isIn(200, 204, 401, 404);
    }

    @Test
    @DisplayName("Phase 16 deletion - an unauthenticated caller cannot delete an account")
    void deletionRequiresAuthentication() throws Exception {
        assertThat(mvc.perform(delete("/api/v1/me")).andReturn().getResponse().getStatus())
                .isIn(401, 403);
    }

    @Test
    @DisplayName("Phase 16 deletion - export is an explicit boundary, not a guessed payload")
    void exportBoundaryIsExplicit() throws Exception {
        Session user = register("del-export-");
        MvcResult result = getAs(user, "/api/v1/me/export");
        // Export is documented as an operational procedure; it must not emit a partial payload.
        assertThat(result.getResponse().getStatus()).isIn(404, 501);
    }
}
