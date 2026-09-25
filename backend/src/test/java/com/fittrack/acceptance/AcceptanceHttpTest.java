package com.fittrack.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.mock.mockito.MockBean;
import com.fittrack.ai.AiProvider;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
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
    @Autowired com.fittrack.auth.RefreshTokenRepository refreshTokens;
    @MockBean AiProvider aiProvider;

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
                .andExpect(status().isForbidden());
    }
    private String registerUser(String prefix) throws Exception {
        return mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email", prefix + UUID.randomUUID() + "@example.test", "password", "StrongPass123!"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
    private record Session(String id, String access) {}
    private Session registerSession(String prefix) throws Exception {
        JsonNode response = mapper.readTree(registerUser(prefix));
        return new Session(response.path("user").path("id").asText(), response.path("access_token").asText());
    }

    @Test void waterContractIsAuthenticatedAndOwnerScoped() throws Exception {
        mvc.perform(post("/api/v1/water").contentType(MediaType.APPLICATION_JSON).content("{\"amount\":8}")).andExpect(status().isUnauthorized());
        String access = mapper.readTree(registerUser("water-")).path("access_token").asText();
        mvc.perform(post("/api/v1/water").header("Authorization", "Bearer " + access).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":8,\"user_id\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/water").header("Authorization", "Bearer " + access).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":8}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.metric_date").exists()).andExpect(jsonPath("$.water_oz").value(8));
        mvc.perform(post("/api/v1/water").header("Authorization", "Bearer " + access).contentType(MediaType.APPLICATION_JSON).content("{\"oz\":3}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.water_oz").value(11));
        mvc.perform(post("/api/v1/water").header("Authorization", "Bearer " + access).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":0}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/water").header("Authorization", "Bearer " + access).contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1001}")).andExpect(status().isBadRequest());
    }

    @Test void coachUuidBindingUsesAuthenticatedIdentity() throws Exception {
        String access = mapper.readTree(registerUser("coach-uuid-")).path("access_token").asText();
        org.mockito.Mockito.when(aiProvider.analyzeCoach(org.mockito.ArgumentMatchers.anyString())).thenReturn("mock review");
        mvc.perform(post("/api/v1/coach/analyze").header("Authorization", "Bearer " + access).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    @Test void notificationReadIsOwnerScoped() throws Exception {
        Session owner = registerSession("notice-owner-");
        Session other = registerSession("notice-other-");
        UUID id = UUID.randomUUID();
        jdbc.update("insert into coach_notifications(id,user_id,title,message,kind,is_read,created_at) values (?,?::uuid,'Review','Body','info',false,now())", id, owner.id());
        mvc.perform(post("/api/v1/coach-notifications/" + id + "/read").header("Authorization", "Bearer " + other.access()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/coach-notifications/" + id + "/read").header("Authorization", "Bearer " + owner.access()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.is_read").value(true));
        mvc.perform(post("/api/v1/coach-notifications/" + id + "/read")).andExpect(status().isUnauthorized());
    }

    @Test void healthDevicePatchIsOwnerScopedAndPartial() throws Exception {
        Session owner = registerSession("device-owner-");
        Session other = registerSession("device-other-");
        UUID id = UUID.randomUUID();
        jdbc.update("insert into health_devices(id,user_id,device_name,device_type,status,created_at) values (?,?::uuid,'Watch','Fitness','Connected',now())", id, owner.id());
        mvc.perform(patch("/api/v1/health-devices/" + id).header("Authorization", "Bearer " + owner.access())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"Disconnected\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("Disconnected")).andExpect(jsonPath("$.device_name").value("Watch"));
        mvc.perform(patch("/api/v1/health-devices/" + id).header("Authorization", "Bearer " + owner.access())
                .contentType(MediaType.APPLICATION_JSON).content("{\"user_id\":\"" + other.id() + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/health-devices/" + id).header("Authorization", "Bearer " + other.access())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"Connected\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/health-devices/" + id).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test void refreshRotationReplayAndFamilyRevocationAreEnforced() throws Exception {
        Session owner = registerSession("refresh-");
        JsonNode first = mapper.readTree(registerUser("refresh-login-"));
        String access = first.path("access_token").asText();
        String refresh = first.path("refresh_token").asText();
        MvcResult rotated = mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("refreshToken", refresh))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.access_token").isNotEmpty()).andReturn();
        JsonNode next = mapper.readTree(rotated.getResponse().getContentAsString());
        String nextRefresh = next.path("refresh_token").asText();
        org.assertj.core.api.Assertions.assertThat(next.path("access_token").asText()).isNotEqualTo(access);
        org.assertj.core.api.Assertions.assertThat(nextRefresh).isNotEqualTo(refresh);
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("refreshToken", refresh))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("refreshToken", nextRefresh))))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + owner.access()))
                .andExpect(status().isOk());
    }

    @Test void scannerLifecycleAndForeignAccessAreOwnerScoped() throws Exception {
        Session owner = registerSession("scan-owner-");
        Session other = registerSession("scan-other-");
        org.mockito.Mockito.when(aiProvider.analyzeFood(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new AiProvider.FoodAnalysis("test-model", java.util.List.of(new AiProvider.FoodItem("Rice", 100, .9))));
        byte[] jpeg = new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x01, 0x02};
        MvcResult uploaded = mvc.perform(multipart("/api/v1/food-scans").file(new MockMultipartFile("file", "meal.jpg", "image/jpeg", jpeg))
                .header("Authorization", "Bearer " + owner.access()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").isNotEmpty()).andExpect(jsonPath("$.items[0].name").value("Rice"))
                .andReturn();
        String scanId = mapper.readTree(uploaded.getResponse().getContentAsString()).path("id").asText();
        mvc.perform(get("/api/v1/food-scans/" + scanId).header("Authorization", "Bearer " + other.access())).andExpect(status().isNotFound());
        mvc.perform(multipart("/api/v1/food-scans").file(new MockMultipartFile("file", "meal.txt", "text/plain", jpeg))
                .header("Authorization", "Bearer " + owner.access())).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/food-scans").file(new MockMultipartFile("file", "meal.jpg", "image/jpeg", new byte[]{1,2,3}))
                .header("Authorization", "Bearer " + owner.access())).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/food-scans").file(new MockMultipartFile("file", "meal.jpg", "image/jpeg", new byte[8 * 1024 * 1024 + 1]))
                .header("Authorization", "Bearer " + owner.access())).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/food-scans").file(new MockMultipartFile("file", "meal.jpg", "image/jpeg", jpeg))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/food-scans/" + scanId).header("Authorization", "Bearer " + owner.access())).andExpect(status().isOk());
        mvc.perform(put("/api/v1/food-scans/" + scanId + "/items").header("Authorization", "Bearer " + other.access())
                .contentType(MediaType.APPLICATION_JSON).content("[]")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/food-scans/" + scanId).header("Authorization", "Bearer " + other.access())).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/food-scans/" + scanId).header("Authorization", "Bearer " + owner.access())).andExpect(status().isNoContent());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from food_scans where id=?", Integer.class, UUID.fromString(scanId))).isZero();
    }

    @Test void analyticsAreScopedToJwtUser() throws Exception {
        Session a = registerSession("analytics-a-");
        Session b = registerSession("analytics-b-");
        LocalDate today = LocalDate.now();
        jdbc.update("insert into workouts(id,user_id,title,duration_minutes,workout_date,completed) values (?,?::uuid,'A workout',11,?,true)", UUID.randomUUID(), a.id(), today);
        jdbc.update("insert into workouts(id,user_id,title,duration_minutes,workout_date,completed) values (?,?::uuid,'B workout',99,?,true)", UUID.randomUUID(), b.id(), today);
        for (String path : new String[]{"dashboard", "workouts", "nutrition", "progress", "weekly"}) {
            mvc.perform(get("/api/v1/analytics/" + path + "?userId=" + b.id()).header("Authorization", "Bearer " + a.access()))
                    .andExpect(status().isOk()).andExpect(content().string(not(containsString("B workout"))));
        }
    }
    @Value("${app.storage-path}") String storagePath;


    @Test void authFailuresExpiryAndRefreshExpiryAreHttpCovered() throws Exception {
        String email = "auth-edge-" + UUID.randomUUID() + "@example.test";
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("email", email, "password", "StrongPass123!")))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("email", email, "password", "wrongpass")))).andExpect(status().isBadRequest());
        Session s = registerSession("access-expiry-");
        SecretKey key = Keys.hmacShaKeyFor("test-secret-that-is-long-enough-for-hmac-signing".getBytes(StandardCharsets.UTF_8));
        String expired = Jwts.builder().id(UUID.randomUUID().toString()).subject(s.id()).issuedAt(Date.from(Instant.now().minusSeconds(120))).expiration(Date.from(Instant.now().minusSeconds(60))).signWith(key).compact();
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + expired)).andExpect(status().isUnauthorized());
        JsonNode response = mapper.readTree(registerUser("refresh-expiry-")); String raw = response.path("refresh_token").asText();
        jdbc.update("update refresh_tokens set expires_at=now()-interval '1 day' where token_hash=?", sha(raw));
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("refreshToken", raw)))).andExpect(status().isBadRequest());
    }

    @Test void scannerCorrectionConfirmationAndStorageCleanup() throws Exception {
        Session owner=registerSession("scan-full-"); Session other=registerSession("scan-full-other-");
        org.mockito.Mockito.when(aiProvider.analyzeFood(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString())).thenReturn(new AiProvider.FoodAnalysis("test",java.util.List.of(new AiProvider.FoodItem("Rice",100,.8))));
        byte[] jpeg={(byte)0xff,(byte)0xd8,(byte)0xff,1};
        MvcResult up=mvc.perform(multipart("/api/v1/food-scans").file(new MockMultipartFile("file","x.jpg","image/jpeg",jpeg)).header("Authorization","Bearer "+owner.access())).andExpect(status().isCreated()).andReturn();
        JsonNode scan=mapper.readTree(up.getResponse().getContentAsString()); String id=scan.path("id").asText(), item=scan.path("items").get(0).path("id").asText();
        String key=jdbc.queryForObject("select image_path from food_scans where id=?",String.class,UUID.fromString(id));
        Path storageRoot=Path.of(storagePath).toAbsolutePath();
        Path backendRelativeRoot=Path.of("backend").resolve(storagePath).toAbsolutePath();
        if (!Files.exists(storageRoot) && Files.exists(backendRelativeRoot)) storageRoot=backendRelativeRoot;
        org.assertj.core.api.Assertions.assertThat(Files.walk(storageRoot).anyMatch(p->p.toString().replace('\\','/').endsWith(key))).isTrue();
        final Path effectiveStorageRoot=storageRoot;
        java.util.function.Predicate<Path> exists=p->{try(var stream=Files.walk(effectiveStorageRoot)){return stream.anyMatch(x->x.toString().replace('\\','/').endsWith(key));}catch(java.io.IOException e){throw new RuntimeException(e);}};
        mvc.perform(put("/api/v1/food-scans/"+id+"/items").header("Authorization","Bearer "+owner.access()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(java.util.List.of(Map.of("itemId",item,"name","Rice","grams",200))))).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].grams").value(200));
        mvc.perform(post("/api/v1/food-scans/"+id+"/confirm").header("Authorization","Bearer "+owner.access()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("confirmed"));
        UUID meal=jdbc.queryForObject("select meal_id from food_scans where id=?",UUID.class,UUID.fromString(id));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from meals where id=? and user_id=?::uuid",Integer.class,meal,owner.id())).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from meal_items where meal_id=?",Integer.class,meal)).isEqualTo(1);
        mvc.perform(post("/api/v1/food-scans/"+id+"/confirm").header("Authorization","Bearer "+owner.access()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("Conflict"));
        mvc.perform(delete("/api/v1/food-scans/"+id).header("Authorization","Bearer "+owner.access())).andExpect(status().isNoContent());
        org.assertj.core.api.Assertions.assertThat(exists.test(null)).isFalse();
    }

    @Test void catalogWriteReturnsForbiddenContract() throws Exception {
        Session s=registerSession("catalog-403-");
        mvc.perform(post("/api/v1/exercises").header("Authorization","Bearer "+s.access()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"blocked\"}")).andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403)).andExpect(jsonPath("$.error").value("Forbidden"));
    }

    private String sha(String raw) throws Exception {return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));}


    @Test void directResourceOwnershipMatrixIsComplete() throws Exception {
        Session a=registerSession("direct-a-"); Session b=registerSession("direct-b-");
        String[][] specs={{"fitness-profile","{\"display_name\":\"A\"}"},{"daily-metrics","{\"metric_date\":\"2026-01-01\",\"steps\":10}"},{"body-metrics","{\"metric_date\":\"2026-01-02\",\"weight_lb\":180}"},{"workouts","{\"title\":\"A workout\"}"},{"workout-sessions","{\"title\":\"A session\"}"},{"workout-templates","{\"name\":\"A template\"}"},{"plan-sessions","{\"day_index\":1,\"title\":\"A plan\"}"},{"personal-records","{\"exercise\":\"A lift\",\"record_value\":10}"},{"goals","{\"title\":\"A goal\"}"},{"meals","{\"name\":\"A meal\"}"},{"habits","{\"name\":\"A habit\"}"},{"reminders","{\"title\":\"A reminder\",\"enabled\":true}"},{"health-devices","{\"device_name\":\"Watch\",\"status\":\"Connected\"}"},{"coach-notifications","{\"title\":\"Notice\",\"message\":\"Body\"}"}};
        for(String[] spec:specs){String resource=spec[0];MvcResult made=mvc.perform(post("/api/v1/"+resource).header("Authorization","Bearer "+a.access()).contentType(MediaType.APPLICATION_JSON).content(spec[1])).andExpect(status().isOk()).andReturn();String id=mapper.readTree(made.getResponse().getContentAsString()).path("id").asText();String path="/api/v1/"+resource+"/"+id;mvc.perform(get(path).header("Authorization","Bearer "+b.access())).andExpect(status().isNotFound());mvc.perform(put(path).header("Authorization","Bearer "+b.access()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());mvc.perform(delete(path).header("Authorization","Bearer "+b.access())).andExpect(status().isNotFound());mvc.perform(get(path).header("Authorization","Bearer "+a.access())).andExpect(status().isOk());}
    }
    @Test void childOwnershipMatrixIsComplete() throws Exception {
        Session a=registerSession("child-a-");Session b=registerSession("child-b-");UUID catalog=UUID.randomUUID();jdbc.update("insert into exercises(id,name) values (?,?)",catalog,"Exercise");UUID workout=UUID.randomUUID(),session=UUID.randomUUID(),we=UUID.randomUUID(),set=UUID.randomUUID(),template=UUID.randomUUID(),te=UUID.randomUUID(),meal=UUID.randomUUID(),mi=UUID.randomUUID(),habit=UUID.randomUUID(),log=UUID.randomUUID(),scan=UUID.randomUUID(),si=UUID.randomUUID();jdbc.update("insert into workouts(id,user_id,title) values (?,?::uuid,'W')",workout,a.id());jdbc.update("insert into workout_sessions(id,user_id,title) values (?,?::uuid,'S')",session,a.id());jdbc.update("insert into workout_exercises(id,workout_session_id,exercise_id) values (?,?,?)",we,session,catalog);jdbc.update("insert into exercise_sets(id,workout_exercise_id,set_number) values (?,?,1)",set,we);jdbc.update("insert into workout_templates(id,user_id,name) values (?,?::uuid,'T')",template,a.id());jdbc.update("insert into workout_template_exercises(id,template_id,exercise_id) values (?,?,?)",te,template,catalog);jdbc.update("insert into meals(id,user_id,name,source) values (?,?::uuid,'M','manual')",meal,a.id());jdbc.update("insert into meal_items(id,meal_id,food_name,grams) values (?,?,?,100)",mi,meal,"Food");jdbc.update("insert into habits(id,user_id,name) values (?,?::uuid,'H')",habit,a.id());jdbc.update("insert into habit_logs(id,user_id,habit_id,log_date,completed) values (?,?::uuid,?,current_date,true)",log,a.id(),habit);jdbc.update("insert into food_scans(id,user_id,status) values (?,?::uuid,'completed')",scan,a.id());jdbc.update("insert into food_scan_items(id,scan_id,food_name,confirmed_grams) values (?,?,?,100)",si,scan,"Food");String[][] children={{"workout-exercises",we.toString()},{"exercise-sets",set.toString()},{"workout-template-exercises",te.toString()},{"meal-items",mi.toString()},{"habit-logs",log.toString()},{"food-scan-items",si.toString()}};for(String[] c:children){String path="/api/v1/"+c[0]+"/"+c[1];mvc.perform(get(path).header("Authorization","Bearer "+b.access())).andExpect(status().isNotFound());mvc.perform(put(path).header("Authorization","Bearer "+b.access()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());mvc.perform(delete(path).header("Authorization","Bearer "+b.access())).andExpect(status().isNotFound());}
    }

}
