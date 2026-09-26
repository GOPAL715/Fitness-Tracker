package com.fittrack.acceptance.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fittrack.ai.AiProvider;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Shared PostgreSQL-backed acceptance harness.
 *
 * <p>Exactly one container is started for the whole JVM and shared by every Spring context in
 * the suite. It is managed manually rather than with {@code @Container}, because the Testcontainers
 * extension would stop the shared instance as soon as the first test class finished and break
 * every later context. The {@link FakeAiProvider} is installed as the primary
 * {@link AiProvider} bean so no test can reach a real external AI API.
 */
public abstract class AbstractAcceptanceTest {

    private static final PostgreSQLContainer<?> POSTGRES = SharedPostgres.INSTANCE;

    private static final Path STORAGE = storageDirectory();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("app.jwt-secret", () -> "test-secret-that-is-long-enough-for-hmac-signing");
        registry.add("app.storage-path", () -> STORAGE.toString());
        // Neutral model names so assertions never depend on production model identifiers.
        registry.add("app.ai-vision-model", () -> "fake-vision-model");
        registry.add("app.ai-text-model", () -> "fake-text-model");
    }

    private static Path storageDirectory() {
        try {
            Path path = Files.createTempDirectory("fittrack-acceptance-storage");
            path.toFile().deleteOnExit();
            return path;
        } catch (Exception e) {
            throw new IllegalStateException("Could not create a temporary storage directory", e);
        }
    }

    @TestConfiguration
    static class FakeProviderConfiguration {
        @Bean @Primary
        AiProvider fakeAiProvider() { return new FakeAiProvider(); }
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper mapper;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected AiProvider aiProvider;

    protected FakeAiProvider fake() {
        assertThat(aiProvider)
                .as("the AI boundary must be the deterministic fake for the whole suite")
                .isInstanceOf(FakeAiProvider.class);
        return (FakeAiProvider) aiProvider;
    }

    @BeforeEach
    void resetFakeProvider() { fake().reset(); }

    public record Session(String id, String access) {}

    protected Session register(String prefix) throws Exception {
        String email = prefix + UUID.randomUUID() + "@example.test";
        MvcResult result = mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("email", email, "password", "StrongPass123!"))))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("registration must succeed for %s", email).isEqualTo(200);
        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        return new Session(body.path("user").path("id").asText(), body.path("access_token").asText());
    }

    protected MvcResult call(Session session, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + session.access())).andReturn();
    }

    protected JsonNode json(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    protected MvcResult getAs(Session session, String path) throws Exception {
        return call(session, get(path));
    }

    protected void assertStatus(MvcResult result, int expected) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as("body was %s", result.getResponse().getContentAsString())
                .isEqualTo(expected);
    }

    /** Proves the structured error envelope: status, error label, and a non-blank message. */
    protected void assertStructuredError(MvcResult result, int status, String errorLabel) throws Exception {
        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).as("body was %s", body).isEqualTo(status);
        JsonNode node = json(result);
        assertThat(node.path("status").asInt()).isEqualTo(status);
        assertThat(node.path("error").asText()).isEqualTo(errorLabel);
        assertThat(node.path("message").asText()).isNotBlank();
    }

    protected void assertUnauthenticated(MockHttpServletRequestBuilder request) throws Exception {
        assertThat(mvc.perform(request).andReturn().getResponse().getStatus()).isEqualTo(401);
    }
}
