package com.fittrack.acceptance;

import com.fittrack.acceptance.support.AbstractAcceptanceTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 16: HTTP rate limiting contract.
 *
 * <p>Proves the 429 shape, the {@code Retry-After} header, and that the authenticated bucket is
 * per user rather than shared. A tight auth limit is applied to this context only.
 */
@SpringBootTest(classes = com.fittrack.api.FitTrackApplication.class)
@org.springframework.test.context.TestPropertySource(properties = {
        // Replaces the suite-wide unlimited default so this class observes real 429 responses.
        "app.rate-limit.auth-requests=3",
        "app.rate-limit.auth-window-seconds=60",
        "app.rate-limit.api-requests=5",
        "app.rate-limit.api-window-seconds=60",
        "app.rate-limit.ai-requests=2",
        "app.rate-limit.ai-window-seconds=60"
})
@AutoConfigureMockMvc
class RateLimitHttpAcceptanceTest extends AbstractAcceptanceTest {
    @Autowired
    com.fittrack.security.RateLimitService limiter;
    @Test
    @DisplayName("Phase 16 rate limit - repeated auth attempts are limited with 429 and Retry-After")
    void authenticationAbuseIsRateLimited() throws Exception {
        // A distinct client address per test keeps buckets independent.
        String client = "203.0.113." + (10 + (int) (Math.random() * 200));
        int limited = 0;
        for (int attempt = 0; attempt < 8; attempt++) {
            MvcResult result = mvc.perform(post("/api/v1/auth/login")
                    .header("X-Forwarded-For", client)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"bruteforce@example.test\",\"password\":\"WrongPassword1!\"}")).andReturn();
            if (result.getResponse().getStatus() == 429) {
                limited++;
                assertThat(result.getResponse().getHeader("Retry-After"))
                        .as("Retry-After must be present when limited").isNotBlank();
                assertThat(result.getResponse().getContentAsString())
                        .contains("rate_limited")
                        .doesNotContain("java.lang").doesNotContain("jdbc:");
                break;
            }
        }
        assertThat(limited).as("brute-force attempts must eventually be limited").isEqualTo(1);
    }

    @Test
    @DisplayName("Phase 16 rate limit - the 429 response never discloses internals")
    void limitResponseIsSafe() throws Exception {
        String client = "198.51.100." + (10 + (int) (Math.random() * 200));
        MvcResult limited = null;
        for (int attempt = 0; attempt < 8 && limited == null; attempt++) {
            MvcResult result = mvc.perform(post("/api/v1/auth/login")
                    .header("X-Forwarded-For", client)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"safe@example.test\",\"password\":\"WrongPassword1!\"}")).andReturn();
            if (result.getResponse().getStatus() == 429) limited = result;
        }
        assertThat(limited).as("request was limited").isNotNull();
        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(limited.getResponse().getContentAsString())
                .contains("\"code\":\"rate_limited\"")
                .doesNotContain("at com.fittrack").doesNotContain("Exception");
    }

    @Test
    @DisplayName("Phase 16 rate limit - authenticated users do not share a bucket")
    void authenticatedBucketsAreNotShared() throws Exception {
        Session first = register("rl-first-");
        Session second = register("rl-second-");
        // Exhaust the general API budget for the first user only.
        for (int i = 0; i < 6; i++) {
            call(first, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .get("/api/v1/me"));
        }
        int firstLimited = 0;
        int secondOk = 0;
        for (int i = 0; i < 3; i++) {
            if (call(first, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .get("/api/v1/me")).getResponse().getStatus() == 429) firstLimited++;
        }
        for (int i = 0; i < 3; i++) {
            if (call(second, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .get("/api/v1/me")).getResponse().getStatus() != 429) secondOk++;
        }
        assertThat(firstLimited).as("the noisy user is limited").isGreaterThan(0);
        assertThat(secondOk).as("an independent user is unaffected").isEqualTo(3);
    }
}
