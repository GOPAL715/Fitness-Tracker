package com.fittrack.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = com.fittrack.api.FitTrackApplication.class)
@AutoConfigureMockMvc
@Testcontainers
class AcceptanceHttpTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        properties.add("app.jwt-secret", () -> "test-secret-that-is-long-enough-for-hmac-signing");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test void flywayCreatedRequiredSchemaAndConstraints() {
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema='public' and table_name in ('app_users','food_scans','food_scan_items','workout_sessions','meal_items')", Integer.class), is(5));
        assertThat(jdbc.queryForObject("select count(*) from information_schema.table_constraints where constraint_type='FOREIGN KEY' and table_name='food_scan_items'", Integer.class), greaterThan(0));
        assertThat(jdbc.queryForObject("select count(*) from information_schema.table_constraints where constraint_type='UNIQUE' and table_name='habit_logs'", Integer.class), greaterThan(0));
    }

    @Test void unauthenticatedProtectedRouteIs401() throws Exception {
        mvc.perform(get("/api/v1/app-data")).andExpect(status().isUnauthorized());
    }

    @Test void registerLoginMeAndLogoutUseHttpSecurityChain() throws Exception {
        String email = "acceptance-" + UUID.randomUUID() + "@example.test";
        MvcResult registered = mvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email", email, "password", "StrongPass123!"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.access_token").isNotEmpty())
                .andExpect(jsonPath("$.refresh_token").isNotEmpty()).andReturn();
        JsonNode tokens = mapper.readTree(registered.getResponse().getContentAsString());
        String access = tokens.path("access_token").asText();
        String refresh = tokens.path("refresh_token").asText();

        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));
        mvc.perform(get("/api/v1/app-data").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("refreshToken", refresh))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("refreshToken", refresh))))
                .andExpect(status().isBadRequest());
    }

    @Test void invalidBearerIs401() throws Exception {
        mvc.perform(get("/api/v1/app-data").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test void analyticsRoutesRequireAuthAndRejectInvertedRange() throws Exception {
        mvc.perform(get("/api/v1/analytics/dashboard")).andExpect(status().isUnauthorized());
        MvcResult result = mvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email", "analytics-" + UUID.randomUUID() + "@example.test", "password", "StrongPass123!"))))
                .andExpect(status().isOk()).andReturn();
        String access = mapper.readTree(result.getResponse().getContentAsString()).path("access_token").asText();
        for (String path : new String[]{"dashboard", "workouts", "nutrition", "progress", "weekly"}) {
            mvc.perform(get("/api/v1/analytics/" + path).header("Authorization", "Bearer " + access))
                    .andExpect(status().isOk()).andExpect(content().string(not(emptyOrNullString())));
        }
        mvc.perform(get("/api/v1/analytics/workouts?from=2026-02-01&to=2026-01-01")
                .header("Authorization", "Bearer " + access)).andExpect(status().isBadRequest());
    }

    @Test void catalogCannotBeWrittenByAuthenticatedUser() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email", "catalog-" + UUID.randomUUID() + "@example.test", "password", "StrongPass123!"))))
                .andExpect(status().isOk()).andReturn();
        String access = mapper.readTree(result.getResponse().getContentAsString()).path("access_token").asText();
        mvc.perform(post("/api/v1/exercises").header("Authorization", "Bearer " + access)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"forbidden\"}"))
                .andExpect(status().isNotFound());
    }
}
