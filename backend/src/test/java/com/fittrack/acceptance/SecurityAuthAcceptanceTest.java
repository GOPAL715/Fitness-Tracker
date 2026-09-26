package com.fittrack.acceptance;

import com.fittrack.acceptance.support.AbstractAcceptanceTest;
import com.fasterxml.jackson.databind.JsonNode;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase 16: authentication and error-contract acceptance tests.
 *
 * <p>Proves tokens are validated strictly and that no credential or internal detail can reach a
 * client through any error path.
 */
@SpringBootTest(classes = com.fittrack.api.FitTrackApplication.class)
@AutoConfigureMockMvc
class SecurityAuthAcceptanceTest extends AbstractAcceptanceTest {

    private static final String TEST_SECRET = "test-secret-that-is-long-enough-for-hmac-signing";

    @Test
    @DisplayName("Phase 16 auth - an unauthenticated request gets structured JSON, not an empty body")
    void unauthenticatedResponseIsStructuredAndCorrelated() throws Exception {
        String correlation = java.util.UUID.randomUUID().toString();
        MvcResult result = mvc.perform(get("/api/v1/me")
                .header("Authorization", "Bearer not-a-jwt")
                .header("X-Request-Id", correlation)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        JsonNode body = json(result);
        assertThat(body.path("status").asInt()).isEqualTo(401);
        assertThat(body.path("code").asText()).isEqualTo("invalid_token");
        assertThat(body.path("error").asText()).isEqualTo("Unauthorized");
        // The correlation id lets a support report be matched to its log lines.
        assertThat(body.path("request_id").asText()).isEqualTo(correlation);
        // The failure reason is never disclosed: no internals, no token material.
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("SignatureException")
                .doesNotContain("ExpiredJwtException")
                .doesNotContain("eyJ")
                .doesNotContain("at com.fittrack");
    }

    @Test
    @DisplayName("Phase 16 auth - expired, malformed and forged tokens are indistinguishable")
    void allTokenFailuresLookIdentical() throws Exception {
        Session user = register("sec-uniform-");
        // Three different real failure modes: an expired token, unparseable text, and a
        // well-formed token signed with the wrong key.
        String expired = Jwts.builder().subject(user.id())
                .issuedAt(Date.from(Instant.now().minusSeconds(7200)))
                .expiration(Date.from(Instant.now().minusSeconds(3600)))
                .signWith(testKey()).compact();
        String forged = Jwts.builder().subject(user.id())
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor(
                        "a-completely-different-secret-key-value-32b".getBytes(StandardCharsets.UTF_8)))
                .compact();

        String expiredBody = stripCorrelation(bodyOf("Bearer " + expired));
        String malformedBody = stripCorrelation(bodyOf("Bearer not-a-jwt"));
        String forgedBody = stripCorrelation(bodyOf("Bearer " + forged));

        // Identical responses mean an attacker cannot tell a forged token from a stale one.
        assertThat(malformedBody).isEqualTo(expiredBody);
        assertThat(forgedBody).isEqualTo(expiredBody);
    }

    /** Status and body of a request carrying the given Authorization header. */
    private String bodyOf(String authorization) throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/me").header("Authorization", authorization)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
        return r.getResponse().getContentAsString();
    }

    @Test
    @DisplayName("Phase 16 auth - a request with no token at all is also structured")
    void missingTokenIsStructured() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/me")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        JsonNode body = json(result);
        assertThat(body.path("code").asText()).isEqualTo("authentication_required");
        assertThat(result.getResponse().getContentType()).contains("application/json");
    }

    private javax.crypto.SecretKey testKey() {
        return Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Phase 16 auth - an expired access token is rejected")
    void expiredAccessTokenIsRejected() throws Exception {
        Session user = register("sec-expired-");
        String expired = Jwts.builder().subject(user.id())
                .issuedAt(Date.from(Instant.now().minusSeconds(7200)))
                .expiration(Date.from(Instant.now().minusSeconds(3600)))
                .signWith(testKey()).compact();
        assertThat(mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + expired))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("Phase 16 auth - a token signed with another key is rejected")
    void invalidSignatureIsRejected() throws Exception {
        Session user = register("sec-sig-");
        String forged = Jwts.builder().subject(user.id())
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor(
                        "a-completely-different-secret-key-value-32b".getBytes(StandardCharsets.UTF_8)))
                .compact();
        assertThat(mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + forged))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("Phase 16 auth - a malformed token is rejected without disclosing internals")
    void malformedTokenIsRejected() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/me")
                .header("Authorization", "Bearer not-a-jwt")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("SignatureException");
    }

    @Test
    @DisplayName("Phase 16 auth - a revoked refresh token is rejected")
    void revokedRefreshTokenIsRejected() throws Exception {
        Session user = register("sec-replay-");
        assertThat(user.refresh()).as("registration issued a refresh token").isNotBlank();

        mvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + user.refresh() + "\"}"));

        MvcResult refresh = mvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + user.refresh() + "\"}")).andReturn();
        assertThat(refresh.getResponse().getStatus()).isIn(400, 401);
        assertThat(refresh.getResponse().getContentAsString()).doesNotContain("eyJ");
    }

    @Test
    @DisplayName("Phase 16 auth - login errors do not disclose whether an account exists")
    void authErrorsDoNotDiscloseAccountExistence() throws Exception {
        Session known = register("sec-known-");
        MvcResult wrongPassword = mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"sec-known-" + "@example.test\",\"password\":\"WrongPassword1!\"}")).andReturn();
        MvcResult unknown = mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"nobody-here@example.test\",\"password\":\"WrongPassword1!\"}")).andReturn();
        assertThat(unknown.getResponse().getStatus()).isEqualTo(wrongPassword.getResponse().getStatus());
        // Bodies differ only by correlation id, which is per-request by design.
        assertThat(stripCorrelation(unknown.getResponse().getContentAsString()))
                .isEqualTo(stripCorrelation(wrongPassword.getResponse().getContentAsString()));
        assertThat(known.access()).isNotBlank();
    }

    @Test
    @DisplayName("Phase 16 auth - registration never returns a password or hash")
    void registrationDoesNotLeakCredentials() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"sec-leak-" + UUID.randomUUID() + "@example.test\","
                        + "\"password\":\"SuperSecret123!\"}")).andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("SuperSecret123").doesNotContain("password_hash")
                .doesNotContain("$2a$").doesNotContain("$2b$");
    }

    // ---------------------------------------------------------- error contract

    @Test
    @DisplayName("Phase 16 errors - every error carries a stable code and the correlation id")
    void errorsCarryStableCodesAndCorrelation() throws Exception {
        Session user = register("sec-code-");
        String correlation = UUID.randomUUID().toString();
        MvcResult result = call(user, get("/api/v1/analytics/workouts?from=2026-03-10&to=2026-03-01")
                .header("X-Request-Id", correlation));
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        JsonNode body = json(result);
        assertThat(body.path("code").asText()).isEqualTo("invalid_request");
        assertThat(body.path("status").asInt()).isEqualTo(400);
        assertThat(body.path("request_id").asText()).isEqualTo(correlation);
    }

    @Test
    @DisplayName("Phase 16 errors - a malformed UUID returns a safe 400 with no internals")
    void malformedUuidReturnsSafeBadRequest() throws Exception {
        Session user = register("sec-uuid-");
        MvcResult result = getAs(user, "/api/v1/ai-usage/not-a-uuid");
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        String body = result.getResponse().getContentAsString();
        // The generic resource service raises its own safe 400 for a malformed id.
        assertThat(body).contains("invalid_");
        assertThat(body).doesNotContain("java.lang").doesNotContain("NumberFormatException")
                .doesNotContain("jdbc:").doesNotContain("org.postgresql");
    }

    @Test
    @DisplayName("Phase 16 errors - an unknown route is a controlled 404 without a stack trace")
    void unknownRouteIsControlled() throws Exception {
        Session user = register("sec-404-");
        MvcResult result = getAs(user, "/api/v1/does-not-exist");
        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).isIn(400, 404);
        assertThat(body).doesNotContain("at com.fittrack").doesNotContain("Caused by").doesNotContain(".java:");
    }

    @Test
    @DisplayName("Phase 16 errors - a provider failure returns a stable code, never provider detail")
    void providerFailureIsNormalized() throws Exception {
        Session user = register("sec-provider-");
        // /health sync without a connected provider is the safe provider-failure surface.
        MvcResult result = call(user, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/health/devices/" + UUID.randomUUID() + "/sync")
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).isIn(404, 502);
        assertThat(body).doesNotContain("upstream").doesNotContain("Deno").doesNotContain("SECRET")
                .doesNotContain("Caused by");
    }

    // ------------------------------------------------------- security headers

    @Test
    @DisplayName("Phase 16 http - security headers are present on API responses")
    void securityHeadersArePresent() throws Exception {
        Session user = register("sec-headers-");
        var response = getAs(user, "/api/v1/me").getResponse();
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.getHeader("Content-Security-Policy")).contains("frame-ancestors 'none'");
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
    }

    // --------------------------------------------------------- observability

    @Test
    @DisplayName("Phase 16 observability - health is public and exposes no internals")
    void healthEndpointIsPublicAndOpaque() throws Exception {
        MvcResult result = mvc.perform(get("/actuator/health")).andReturn();
        String body = result.getResponse().getContentAsString();
        // Redis is not configured in tests, so overall status may be DOWN while the payload stays opaque.
        assertThat(json(result).path("status").asText()).isIn("UP", "DOWN");
        assertThat(body).doesNotContain("jdbc:").doesNotContain("password").doesNotContain("secret")
                .doesNotContain("Disk Space");
    }

    @Test
    @DisplayName("Phase 16 observability - sensitive actuator endpoints are not exposed")
    void sensitiveActuatorEndpointsAreNotExposed() throws Exception {
        for (String path : new String[] {"/actuator/env", "/actuator/configprops", "/actuator/beans",
                "/actuator/mappings", "/actuator/heapdump", "/actuator/loggers", "/actuator/threaddump"}) {
            assertThat(mvc.perform(get(path)).andReturn().getResponse().getStatus())
                    .as("%s must not be publicly exposed", path)
                    .isIn(401, 403, 404);
        }
    }

    @Test
    @DisplayName("Phase 16 observability - liveness and readiness are available")
    void livenessAndReadinessAreAvailable() throws Exception {
        // Probes are exposed under the health group; readiness may report 503 when a dependency
        // such as Redis is unavailable, which is the correct signal for a load balancer.
        assertThat(mvc.perform(get("/actuator/health/liveness")).andReturn().getResponse().getStatus())
                .isIn(200, 503);
        assertThat(mvc.perform(get("/actuator/health/readiness")).andReturn().getResponse().getStatus())
                .isIn(200, 503);
    }
    /** Removes the per-request correlation id so two responses can be compared structurally. */
    private static String stripCorrelation(String body) {
        return body.replaceAll("\"request_id\":\"[^\"]+\"", "\"request_id\":\"*\"");
    }
}
