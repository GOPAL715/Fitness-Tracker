package com.fittrack.acceptance;

import com.fittrack.acceptance.support.AbstractAcceptanceTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Reproduces and guards the reported "login fails after logout" defect.
 *
 * <p>The pre-existing auth test covered register through logout, but never signed back in
 * afterwards, so a failure that only appears on a second login was invisible to the suite.
 */
@org.springframework.boot.test.context.SpringBootTest(classes = com.fittrack.api.FitTrackApplication.class)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class AuthReloginAcceptanceTest extends AbstractAcceptanceTest {

    private static final String PASSWORD = "StrongPass123!";

    @Test
    @DisplayName("BUG - login after logout must succeed and issue a fresh refresh token")
    void loginAfterLogoutIssuesFreshTokens() throws Exception {
        String email = registerUser("relogin-");

        String firstAccess = login(email).path("access_token").asText();
        String revoked = login(email).path("refresh_token").asText();

        logout(revoked);

        // This is the step that used to return HTTP 500.
        MvcResult relogin = loginRaw(email);
        assertThat(relogin.getResponse().getStatus())
                .as("login after logout must succeed").isEqualTo(200);

        JsonNode tokens = json(relogin);
        assertThat(tokens.path("access_token").asText()).as("a new access token is issued").isNotBlank();
        assertThat(tokens.path("refresh_token").asText()).as("a new refresh token is issued").isNotBlank();
        assertThat(tokens.path("refresh_token").asText())
                .as("the new refresh token must differ from the revoked one").isNotEqualTo(revoked);
        assertThat(tokens.path("access_token").asText()).isNotEqualTo(firstAccess);

        assertThat(isRevoked(email, tokens.path("refresh_token").asText()))
                .as("the new refresh token is active").isFalse();

        // The new access token must genuinely authenticate, and must still carry its claims:
        // building it is what failed before.
        MvcResult me = mvc.perform(get("/api/v1/auth/me")
                .header("Authorization", "Bearer " + tokens.path("access_token").asText())).andReturn();
        assertThat(me.getResponse().getStatus()).as("the new access token authenticates").isEqualTo(200);
        assertThat(json(me).path("email").asText()).isEqualTo(email);

        // The roles claim must still be present after being snapshotted: a token that dropped
        // it would authenticate but silently lose its authorization data.
        String payload = new String(java.util.Base64.getUrlDecoder().decode(
                tokens.path("access_token").asText().split("[.]")[1]),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(payload).as("the access token keeps its claims").contains("\"roles\"");
        assertThat(rotateRaw(tokens.path("refresh_token").asText()).getResponse().getStatus())
                .as("the new refresh token can be rotated").isEqualTo(200);
    }

    @Test
    @DisplayName("BUG - the revoked refresh token stays revoked and cannot be reused")
    void revokedRefreshTokenStaysRevoked() throws Exception {
        String email = registerUser("relogin-revoke-");
        String revoked = login(email).path("refresh_token").asText();
        logout(revoked);

        assertThat(isRevoked(email, revoked)).as("logout revoked the token").isTrue();

        String fresh = login(email).path("refresh_token").asText();
        assertThat(fresh).isNotEqualTo(revoked);

        // The old token must stay revoked after a later login, and must not work.
        assertThat(isRevoked(email, revoked))
                .as("logging in again must not un-revoke the old token").isTrue();
        assertThat(rotateRaw(revoked).getResponse().getStatus())
                .as("the revoked token cannot be reused").isIn(400, 401);
    }

    @Test
    @DisplayName("BUG - repeated login/logout cycles all succeed")
    void repeatedLoginLogoutCyclesSucceed() throws Exception {
        String email = registerUser("relogin-cycle-");
        for (int cycle = 1; cycle <= 4; cycle++) {
            MvcResult loggedIn = loginRaw(email);
            assertThat(loggedIn.getResponse().getStatus())
                    .as("login cycle %d must succeed", cycle).isEqualTo(200);
            String refresh = json(loggedIn).path("refresh_token").asText();
            assertThat(refresh).as("cycle %d issues a token", cycle).isNotBlank();
            assertThat(logout(refresh).getResponse().getStatus())
                    .as("logout cycle %d must succeed", cycle).isIn(200, 204);
            assertThat(isRevoked(email, refresh))
                    .as("cycle %d revoked its token", cycle).isTrue();
        }
    }
    @Test
    @DisplayName("BUG - replay detection still revokes the family after a logout and re-login")
    void replayDetectionStillRevokesFamily() throws Exception {
        String email = registerUser("relogin-replay-");
        String original = login(email).path("refresh_token").asText();

        // Rotate once, creating a replacement in the same family.
        MvcResult rotated = rotateRaw(original);
        assertThat(rotated.getResponse().getStatus()).isEqualTo(200);
        String replacement = json(rotated).path("refresh_token").asText();

        // Replaying the consumed original must be detected and revoke the whole family.
        assertThat(rotateRaw(original).getResponse().getStatus()).isIn(400, 401);
        assertThat(isRevoked(email, replacement))
                .as("family revocation must still cover the replacement token").isTrue();
    }

    // ------------------------------------------------------------- helpers

    private String registerUser(String prefix) throws Exception {
        String email = prefix + UUID.randomUUID() + "@example.test";
        MvcResult result = mvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("registration must succeed").isEqualTo(200);
        return email;
    }

    private MvcResult loginRaw(String email) throws Exception {
        return mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andReturn();
    }

    private JsonNode login(String email) throws Exception {
        MvcResult result = loginRaw(email);
        assertThat(result.getResponse().getStatus()).as("login must succeed").isEqualTo(200);
        return json(result);
    }

    private MvcResult logout(String refreshToken) throws Exception {
        return mvc.perform(post("/api/v1/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
                .andReturn();
    }

    private MvcResult rotateRaw(String refreshToken) throws Exception {
        return mvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
                .andReturn();
    }

    /**
     * Whether the supplied raw token is currently stored as revoked.
     *
     * <p>The raw token is hashed exactly as the service does, so no token material is ever read
     * back out of the database or written to a log.
     */
    private boolean isRevoked(String email, String rawToken) {
        String hash = sha256(rawToken);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select t.revoked from refresh_tokens t join app_users u on u.id = t.user_id "
                        + "where u.email = ? and t.token_hash = ?", email, hash);
        assertThat(rows).as("the refresh token row must exist").hasSize(1);
        return (Boolean) rows.get(0).get("revoked");
    }

    /** Mirrors the service's storage hash. Raw tokens are never persisted or logged. */
    private static String sha256(String raw) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}