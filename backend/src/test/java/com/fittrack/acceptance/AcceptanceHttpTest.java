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
import java.util.List;
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

    @Test void compositeSessionRollsBackInvalidCatalogReference() throws Exception {
        Session owner=registerSession("composite-session-"); UUID ex=UUID.randomUUID(); jdbc.update("insert into exercises(id,name) values (?,?)",ex,"Exercise");
        String body=mapper.writeValueAsString(Map.of("session",Map.of("title","Session","workout_type","Strength","duration_minutes",10,"perceived_effort",7,"completed",true),"exercises",java.util.List.of(Map.of("exercise_id",ex,"order_index",0,"sets",java.util.List.of(Map.of("set_number",1,"reps",8,"weight",100,"completed",true))))));
        mvc.perform(post("/api/v1/workout-sessions/complete").header("Authorization","Bearer "+owner.access()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        UUID bad=UUID.randomUUID(); String invalid=body.replace(ex.toString(),bad.toString());
        mvc.perform(post("/api/v1/workout-sessions/complete").header("Authorization","Bearer "+owner.access()).contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from workout_sessions where user_id=?::uuid and title='Session'",Integer.class,owner.id()),is(1));
    }

    @Test void compositeTemplateAndMealRollbackOnMissingCatalogReference() throws Exception {
        Session owner=registerSession("composite-other-"); UUID ex=UUID.randomUUID(); jdbc.update("insert into exercises(id,name) values (?,?)",ex,"Exercise");
        String session = mapper.writeValueAsString(Map.of("session", Map.of("title", "Session2", "workout_type", "Strength", "duration_minutes", 10, "perceived_effort", 7, "completed", true), "exercises", java.util.List.of(Map.of("exercise_id", ex, "order_index", 0, "sets", java.util.List.of(Map.of("set_number", 1, "reps", 8, "completed", true))))));
        mvc.perform(post("/api/v1/workout-sessions/complete").header("Authorization","Bearer "+owner.access()).contentType(MediaType.APPLICATION_JSON).content(session)).andExpect(status().isOk());
        String template=mapper.writeValueAsString(Map.of("template",Map.of("name","Template2","workout_type","Strength","estimated_minutes",30,"favorite",false),"exercises",java.util.List.of(Map.of("exercise_id",UUID.randomUUID(),"order_index",0,"target_sets",3,"target_reps","8-12"))));
        mvc.perform(post("/api/v1/workout-templates/complete").header("Authorization","Bearer "+owner.access()).contentType(MediaType.APPLICATION_JSON).content(template)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from workout_templates where name='Template2'",Integer.class),is(0));
        UUID food=UUID.randomUUID(); jdbc.update("insert into foods(id,name,serving_size,calories) values (?,?,100,100)",food,"Food");
        String meal=mapper.writeValueAsString(Map.of("meal",Map.of("meal_date",LocalDate.now().toString(),"meal_type","LUNCH","name","Meal2","source","manual"),"items",java.util.List.of(Map.of("food_id",food,"grams",100,"quantity",1))));
        mvc.perform(post("/api/v1/meals/complete").header("Authorization","Bearer "+owner.access()).contentType(MediaType.APPLICATION_JSON).content(meal)).andExpect(status().isOk());
        String invalidMeal=meal.replace(food.toString(),UUID.randomUUID().toString());
        mvc.perform(post("/api/v1/meals/complete").header("Authorization","Bearer "+owner.access()).contentType(MediaType.APPLICATION_JSON).content(invalidMeal)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from meals where name='Meal2'",Integer.class),is(1));
    }

    @Test void compositeUserIdIsNeverUsedForOwnership() throws Exception {
        Session a=registerSession("composite-owner-"); Session b=registerSession("composite-other-owner-"); UUID ex=UUID.randomUUID(); jdbc.update("insert into exercises(id,name) values (?,?)",ex,"Exercise");
        String body=mapper.writeValueAsString(Map.of("user_id",b.id(),"session",Map.of("title","Owned","workout_type","Strength","duration_minutes",10,"perceived_effort",7,"completed",true),"exercises",java.util.List.of(Map.of("exercise_id",ex,"order_index",0,"sets",java.util.List.of(Map.of("set_number",1,"reps",8,"completed",true))))));
        mvc.perform(post("/api/v1/workout-sessions/complete").header("Authorization","Bearer "+a.access()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from workout_sessions where user_id=?::uuid and title='Owned'",Integer.class,a.id()),is(1));
        assertThat(jdbc.queryForObject("select count(*) from workout_sessions where user_id=?::uuid and title='Owned'",Integer.class,b.id()),is(0));
}


    private Map<String, Integer> compositeCounts() {
        return Map.of(
                "workout_sessions", jdbc.queryForObject("select count(*) from workout_sessions", Integer.class),
                "workout_exercises", jdbc.queryForObject("select count(*) from workout_exercises", Integer.class),
                "exercise_sets", jdbc.queryForObject("select count(*) from exercise_sets", Integer.class),
                "workout_templates", jdbc.queryForObject("select count(*) from workout_templates", Integer.class),
                "workout_template_exercises", jdbc.queryForObject("select count(*) from workout_template_exercises", Integer.class),
                "meals", jdbc.queryForObject("select count(*) from meals", Integer.class),
                "meal_items", jdbc.queryForObject("select count(*) from meal_items", Integer.class));
    }

    private void assertNoCompositeGrowth(Map<String, Integer> before) {
        assertThat(compositeCounts(), is(before));
    }

    private void assertStructuredBadRequest(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus(), is(400));
        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("status").asInt(), is(400));
        assertThat(body.path("error").asText(), is("Bad Request"));
        assertThat(body.path("message").asText(), not(emptyString()));
    }

    @Test void compositeSessionSuccessPersistsExactGraph() throws Exception {
        Session owner = registerSession("composite-session-success-");
        UUID exercise = UUID.randomUUID();
        jdbc.update("insert into exercises(id,name) values (?,?)", exercise, "Bench Press");
        String body = mapper.writeValueAsString(Map.of(
                "session", Map.of("title", "Explicit session", "workout_type", "Strength", "duration_minutes", 47, "perceived_effort", 8, "notes", "felt strong", "completed", true),
                "exercises", List.of(Map.of("exercise_id", exercise, "order_index", 3, "notes", "left side", "sets", List.of(
                        Map.of("set_number", 1, "reps", 8, "weight", "125.50", "rpe", "8.25", "completed", true),
                        Map.of("set_number", 2, "reps", 6, "weight", "130.00", "rpe", "8.50", "completed", true))))));
        MvcResult result = mvc.perform(post("/api/v1/workout-sessions/complete")
                .header("Authorization", "Bearer " + owner.access()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").isNotEmpty()).andReturn();
        UUID sessionId = UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).path("id").asText());
        assertThat(jdbc.queryForObject("select count(*) from workout_sessions where title='Explicit session' and user_id=?::uuid", Integer.class, owner.id()), is(1));
        Map<String, Object> session = jdbc.queryForMap("select user_id,title,workout_type,duration_minutes,perceived_effort,notes,completed,completed_at from workout_sessions where id=?", sessionId);
        assertThat(session.get("user_id").toString(), is(owner.id()));
        assertThat(session.get("title"), is((Object) "Explicit session"));
        assertThat(session.get("workout_type"), is((Object) "Strength"));
        assertThat(session.get("duration_minutes"), is((Object) 47));
        assertThat(session.get("perceived_effort"), is((Object) 8));
        assertThat(session.get("notes"), is((Object) "felt strong"));
        assertThat(session.get("completed"), is((Object) true));
        assertThat(session.get("completed_at"), is(notNullValue()));
        List<Map<String, Object>> children = jdbc.queryForList("select id,workout_session_id,exercise_id,order_index,notes from workout_exercises where workout_session_id=? order by order_index", sessionId);
        assertThat(children, hasSize(1));
        UUID childId = (UUID) children.get(0).get("id");
        assertThat(children.get(0).get("workout_session_id"), is((Object) sessionId));
        assertThat(children.get(0).get("exercise_id"), is((Object) exercise));
        assertThat(children.get(0).get("order_index"), is((Object) 3));
        assertThat(children.get(0).get("notes"), is((Object) "left side"));
        List<Map<String, Object>> sets = jdbc.queryForList("select set_number,reps,weight,rpe,completed from exercise_sets where workout_exercise_id=? order by set_number", childId);
        assertThat(sets, hasSize(2));
        assertThat(sets.get(0).get("set_number"), is((Object) 1));
        assertThat(sets.get(0).get("reps"), is((Object) 8));
        assertThat(((java.math.BigDecimal) sets.get(0).get("weight")).compareTo(new java.math.BigDecimal("125.50")), is(0));
        assertThat(((java.math.BigDecimal) sets.get(0).get("rpe")).compareTo(new java.math.BigDecimal("8.25")), is(0));
        assertThat(sets.get(0).get("completed"), is((Object) true));
        assertThat(sets.get(1).get("set_number"), is((Object) 2));
        assertThat(sets.get(1).get("reps"), is((Object) 6));
        assertThat(((java.math.BigDecimal) sets.get(1).get("weight")).compareTo(new java.math.BigDecimal("130.00")), is(0));
        assertThat(((java.math.BigDecimal) sets.get(1).get("rpe")).compareTo(new java.math.BigDecimal("8.50")), is(0));
    }


    @Test void compositeTemplateRollbackLeavesNoParentOrChildRows() throws Exception {
        Session owner = registerSession("composite-template-rollback-");
        UUID valid = UUID.randomUUID(), invalid = UUID.randomUUID();
        jdbc.update("insert into exercises(id,name) values (?,?)", valid, "Valid exercise");
        Map<String, Integer> before = compositeCounts();
        Map<String, Object> body = objectMap(
                "template", objectMap("name", "Rolled back template", "workout_type", "Strength", "estimated_minutes", 40, "favorite", false),
                "exercises", java.util.List.of(
                        objectMap("exercise_id", valid, "order_index", 0, "target_sets", 3, "target_reps", "8-12"),
                        objectMap("exercise_id", invalid, "order_index", 1, "target_sets", 3, "target_reps", "8-12")));
        MvcResult result = mvc.perform(post("/api/v1/workout-templates/complete")
                .header("Authorization", "Bearer " + owner.access()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body))).andReturn();
        assertThat(result.getResponse().getStatus(), is(404));
        assertNoCompositeGrowth(before);
    }

    @Test void compositeMealSuccessPersistsExactGraphAndNutrition() throws Exception {
        Session owner = registerSession("composite-meal-success-");
        UUID food = UUID.randomUUID();
        jdbc.update("insert into foods(id,name,serving_size,calories,protein_g,carbs_g,fat_g,fiber_g) values (?,?,?,?,?,?,?,?)",
                food, "Chicken", 100, 250, 20, 30, 10, 5);
        LocalDate mealDate = LocalDate.of(2026, 9, 25);
        Map<String, Object> body = objectMap(
                "meal", objectMap("meal_date", mealDate, "meal_type", "DINNER", "name", "Explicit meal", "source", "manual"),
                "items", java.util.List.of(objectMap("food_id", food, "grams", "150.00", "quantity", "1.50")));
        MvcResult result = mvc.perform(post("/api/v1/meals/complete")
                .header("Authorization", "Bearer " + owner.access()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn();
        UUID mealId = UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).path("id").asText());
        assertThat(jdbc.queryForObject("select count(*) from meals where name='Explicit meal' and user_id=?::uuid", Integer.class, owner.id()), is(1));
        Map<String, Object> parent = jdbc.queryForMap("select user_id,meal_date,meal_type,name,source,calories,protein_g,carbs_g,fat_g,fiber_g from meals where id=?", mealId);
        assertThat(parent.get("user_id").toString(), is(owner.id()));
        assertThat(parent.get("meal_date").toString(), is(mealDate.toString()));
        assertThat(parent.get("meal_type"), is((Object) "DINNER"));
        assertThat(parent.get("name"), is((Object) "Explicit meal"));
        assertThat(parent.get("source"), is((Object) "manual"));
        assertThat(((java.math.BigDecimal) parent.get("calories")).compareTo(new java.math.BigDecimal("375.00")), is(0));
        assertThat(((java.math.BigDecimal) parent.get("protein_g")).compareTo(new java.math.BigDecimal("30.00")), is(0));
        assertThat(((java.math.BigDecimal) parent.get("carbs_g")).compareTo(new java.math.BigDecimal("45.00")), is(0));
        assertThat(((java.math.BigDecimal) parent.get("fat_g")).compareTo(new java.math.BigDecimal("15.00")), is(0));
        assertThat(((java.math.BigDecimal) parent.get("fiber_g")).compareTo(new java.math.BigDecimal("7.50")), is(0));
        List<Map<String, Object>> items = jdbc.queryForList("select meal_id,food_id,quantity,grams,calories,protein_g,carbs_g,fat_g,fiber_g from meal_items where meal_id=?", mealId);
        assertThat(items, hasSize(1));
        assertThat(items.get(0).get("meal_id"), is((Object) mealId));
        assertThat(items.get(0).get("food_id"), is((Object) food));
        assertThat(((java.math.BigDecimal) items.get(0).get("quantity")).compareTo(new java.math.BigDecimal("1.50")), is(0));
        assertThat(((java.math.BigDecimal) items.get(0).get("grams")).compareTo(new java.math.BigDecimal("150.00")), is(0));
        assertThat(((java.math.BigDecimal) items.get(0).get("calories")).compareTo(new java.math.BigDecimal("375.00")), is(0));
        assertThat(((java.math.BigDecimal) items.get(0).get("protein_g")).compareTo(new java.math.BigDecimal("30.00")), is(0));
        assertThat(((java.math.BigDecimal) items.get(0).get("carbs_g")).compareTo(new java.math.BigDecimal("45.00")), is(0));
        assertThat(((java.math.BigDecimal) items.get(0).get("fat_g")).compareTo(new java.math.BigDecimal("15.00")), is(0));
        assertThat(((java.math.BigDecimal) items.get(0).get("fiber_g")).compareTo(new java.math.BigDecimal("7.50")), is(0));
    }

    @Test void compositeMealRollbackLeavesNoParentOrChildRows() throws Exception {
        Session owner = registerSession("composite-meal-rollback-");
        UUID valid = UUID.randomUUID(), invalid = UUID.randomUUID();
        jdbc.update("insert into foods(id,name,serving_size,calories,protein_g,carbs_g,fat_g,fiber_g) values (?,?,?,?,?,?,?,?)", valid, "Valid food", 100, 100, 10, 10, 5, 2);
        Map<String, Integer> before = compositeCounts();
        Map<String, Object> body = objectMap(
                "meal", objectMap("meal_date", LocalDate.now(), "meal_type", "LUNCH", "name", "Rolled back meal", "source", "manual"),
                "items", java.util.List.of(objectMap("food_id", valid, "grams", 100, "quantity", 1), objectMap("food_id", invalid, "grams", 100, "quantity", 1)));
        MvcResult result = mvc.perform(post("/api/v1/meals/complete")
                .header("Authorization", "Bearer " + owner.access()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body))).andReturn();
        assertThat(result.getResponse().getStatus(), is(404));
        assertNoCompositeGrowth(before);
    }


    private Map<String, Object> objectMap(Object... values) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put(values[i].toString(), values[i + 1]);
        return result;
    }

    @Test void compositeSessionRollbackLeavesNoGraphRows() throws Exception {
        Session owner = registerSession("composite-session-rollback-");
        UUID valid = UUID.randomUUID(), invalid = UUID.randomUUID();
        jdbc.update("insert into exercises(id,name) values (?,?)", valid, "Valid exercise");
        Map<String, Integer> before = compositeCounts();
        Map<String, Object> body = objectMap(
                "session", objectMap("title", "Rolled back session", "workout_type", "Strength", "duration_minutes", 30, "perceived_effort", 7, "completed", true),
                "exercises", java.util.List.of(
                        objectMap("exercise_id", valid, "order_index", 0, "sets", java.util.List.of(objectMap("set_number", 1, "reps", 8, "weight", 100, "completed", true))),
                        objectMap("exercise_id", invalid, "order_index", 1, "sets", java.util.List.of(objectMap("set_number", 1, "reps", 8, "weight", 100, "completed", true)))));
        MvcResult result = mvc.perform(post("/api/v1/workout-sessions/complete")
                .header("Authorization", "Bearer " + owner.access()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body))).andReturn();
        assertThat(result.getResponse().getStatus(), is(404));
        assertNoCompositeGrowth(before);
    }

    @Test void compositeTemplateSuccessPersistsExactGraph() throws Exception {
        Session owner = registerSession("composite-template-success-");
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        jdbc.update("insert into exercises(id,name) values (?,?)", first, "Squat");
        jdbc.update("insert into exercises(id,name) values (?,?)", second, "Row");
        Map<String, Object> body = objectMap(
                "template", objectMap("name", "Explicit template", "description", "Full body", "workout_type", "Strength", "estimated_minutes", 55, "favorite", true),
                "exercises", java.util.List.of(
                        objectMap("exercise_id", first, "order_index", 2, "target_sets", 4, "target_reps", "5-5", "target_weight", "225.00"),
                        objectMap("exercise_id", second, "order_index", 3, "target_sets", 3, "target_reps", "8-12", "target_weight", "95.50")));
        MvcResult result = mvc.perform(post("/api/v1/workout-templates/complete")
                .header("Authorization", "Bearer " + owner.access()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn();
        UUID templateId = UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).path("id").asText());
        assertThat(jdbc.queryForObject("select count(*) from workout_templates where name='Explicit template' and user_id=?::uuid", Integer.class, owner.id()), is(1));
        Map<String, Object> parent = jdbc.queryForMap("select user_id,name,description,workout_type,estimated_minutes,is_favorite from workout_templates where id=?", templateId);
        assertThat(parent.get("user_id").toString(), is(owner.id()));
        assertThat(parent.get("name"), is((Object) "Explicit template"));
        assertThat(parent.get("description"), is((Object) "Full body"));
        assertThat(parent.get("workout_type"), is((Object) "Strength"));
        assertThat(parent.get("estimated_minutes"), is((Object) 55));
        assertThat(parent.get("is_favorite"), is((Object) true));
        List<Map<String, Object>> children = jdbc.queryForList("select template_id,exercise_id,order_index,target_sets,target_reps,target_weight from workout_template_exercises where template_id=? order by order_index", templateId);
        assertThat(children, hasSize(2));
        assertThat(children.get(0).get("template_id"), is((Object) templateId));
        assertThat(children.get(0).get("exercise_id"), is((Object) first));
        assertThat(children.get(0).get("order_index"), is((Object) 2));
        assertThat(children.get(0).get("target_sets"), is((Object) 4));
        assertThat(children.get(0).get("target_reps"), is((Object) "5-5"));
        assertThat(((java.math.BigDecimal) children.get(0).get("target_weight")).compareTo(new java.math.BigDecimal("225.00")), is(0));
        assertThat(children.get(1).get("template_id"), is((Object) templateId));
        assertThat(children.get(1).get("exercise_id"), is((Object) second));
        assertThat(children.get(1).get("order_index"), is((Object) 3));
    }

    @Test void compositeTwoUserIsolationKeepsSharedCatalogReferencesSafe() throws Exception {
        Session a = registerSession("composite-isolation-a-");
        Session b = registerSession("composite-isolation-b-");
        UUID exercise = UUID.randomUUID();
        UUID food = UUID.randomUUID();
        jdbc.update("insert into exercises(id,name) values (?,?)", exercise, "Shared exercise");
        jdbc.update("insert into foods(id,name,serving_size,calories,protein_g,carbs_g,fat_g,fiber_g) values (?,?,?,?,?,?,?,?)", food, "Shared food", 100, 100, 10, 10, 5, 2);
        Map<String, Integer> before = compositeCounts();
        String session = mapper.writeValueAsString(objectMap("session", objectMap("title", "A isolation session", "workout_type", "Strength", "duration_minutes", 20, "perceived_effort", 6, "completed", true), "exercises", List.of(objectMap("exercise_id", exercise, "order_index", 0, "sets", List.of(objectMap("set_number", 1, "reps", 8, "completed", true))))));
        MvcResult sessionResult = mvc.perform(post("/api/v1/workout-sessions/complete").header("Authorization", "Bearer " + a.access()).contentType(MediaType.APPLICATION_JSON).content(session)).andExpect(status().isOk()).andReturn();
        String sessionId = mapper.readTree(sessionResult.getResponse().getContentAsString()).path("id").asText();
        String template = mapper.writeValueAsString(objectMap("template", objectMap("name", "A isolation template", "workout_type", "Strength", "estimated_minutes", 20, "favorite", false), "exercises", List.of(objectMap("exercise_id", exercise, "order_index", 0, "target_sets", 3, "target_reps", "8-12"))));
        MvcResult templateResult = mvc.perform(post("/api/v1/workout-templates/complete").header("Authorization", "Bearer " + a.access()).contentType(MediaType.APPLICATION_JSON).content(template)).andExpect(status().isOk()).andReturn();
        String templateId = mapper.readTree(templateResult.getResponse().getContentAsString()).path("id").asText();
        String meal = mapper.writeValueAsString(objectMap("meal", objectMap("meal_date", LocalDate.now(), "meal_type", "SNACK", "name", "A isolation meal", "source", "manual"), "items", List.of(objectMap("food_id", food, "grams", 100, "quantity", 1))));
        MvcResult mealResult = mvc.perform(post("/api/v1/meals/complete").header("Authorization", "Bearer " + a.access()).contentType(MediaType.APPLICATION_JSON).content(meal)).andExpect(status().isOk()).andReturn();
        String mealId = mapper.readTree(mealResult.getResponse().getContentAsString()).path("id").asText();
        assertThat(jdbc.queryForObject("select user_id from workout_sessions where id=?", UUID.class, UUID.fromString(sessionId)).toString(), is(a.id()));
        assertThat(jdbc.queryForObject("select user_id from workout_templates where id=?", UUID.class, UUID.fromString(templateId)).toString(), is(a.id()));
        assertThat(jdbc.queryForObject("select user_id from meals where id=?", UUID.class, UUID.fromString(mealId)).toString(), is(a.id()));
        assertThat(jdbc.queryForObject("select count(*) from workout_sessions where user_id=?::uuid", Integer.class, b.id()), is(0));
        assertThat(jdbc.queryForObject("select count(*) from workout_templates where user_id=?::uuid", Integer.class, b.id()), is(0));
        assertThat(jdbc.queryForObject("select count(*) from meals where user_id=?::uuid", Integer.class, b.id()), is(0));
        assertThat(jdbc.queryForObject("select count(*) from exercises where id=?", Integer.class, exercise), is(1));
        assertThat(jdbc.queryForObject("select count(*) from foods where id=?", Integer.class, food), is(1));
        assertThat(compositeCounts().get("workout_sessions"), greaterThan(before.get("workout_sessions")));
    }


    @Test void compositeForgedUserIdCannotChangeAnyAggregateOwner() throws Exception {
        Session a = registerSession("composite-forged-a-");
        Session b = registerSession("composite-forged-b-");
        UUID exercise = UUID.randomUUID();
        UUID food = UUID.randomUUID();
        jdbc.update("insert into exercises(id,name) values (?,?)", exercise, "Shared exercise");
        jdbc.update("insert into foods(id,name,serving_size,calories,protein_g,carbs_g,fat_g,fiber_g) values (?,?,?,?,?,?,?,?)", food, "Shared food", 100, 100, 10, 10, 5, 2);
        String forged = b.id();
        Map<String, Object> session = objectMap("user_id", forged, "session", objectMap("title", "Forged session", "workout_type", "Strength", "duration_minutes", 20, "perceived_effort", 6, "completed", true), "exercises", List.of(objectMap("exercise_id", exercise, "order_index", 0, "sets", List.of(objectMap("set_number", 1, "reps", 8, "completed", true)))));
        MvcResult sessionResult = mvc.perform(post("/api/v1/workout-sessions/complete").header("Authorization", "Bearer " + a.access()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(session))).andExpect(status().isOk()).andReturn();
        String sessionId = mapper.readTree(sessionResult.getResponse().getContentAsString()).path("id").asText();
        Map<String, Object> template = objectMap("user_id", forged, "template", objectMap("name", "Forged template", "workout_type", "Strength", "estimated_minutes", 20, "favorite", false), "exercises", List.of(objectMap("exercise_id", exercise, "order_index", 0, "target_sets", 3, "target_reps", "8-12")));
        MvcResult templateResult = mvc.perform(post("/api/v1/workout-templates/complete").header("Authorization", "Bearer " + a.access()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(template))).andExpect(status().isOk()).andReturn();
        String templateId = mapper.readTree(templateResult.getResponse().getContentAsString()).path("id").asText();
        Map<String, Object> meal = objectMap("user_id", forged, "meal", objectMap("meal_date", LocalDate.now(), "meal_type", "SNACK", "name", "Forged meal", "source", "manual"), "items", List.of(objectMap("food_id", food, "grams", 100, "quantity", 1)));
        MvcResult mealResult = mvc.perform(post("/api/v1/meals/complete").header("Authorization", "Bearer " + a.access()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(meal))).andExpect(status().isOk()).andReturn();
        String mealId = mapper.readTree(mealResult.getResponse().getContentAsString()).path("id").asText();
        assertThat(jdbc.queryForObject("select user_id from workout_sessions where id=?", UUID.class, UUID.fromString(sessionId)).toString(), is(a.id()));
        assertThat(jdbc.queryForObject("select user_id from workout_templates where id=?", UUID.class, UUID.fromString(templateId)).toString(), is(a.id()));
        assertThat(jdbc.queryForObject("select user_id from meals where id=?", UUID.class, UUID.fromString(mealId)).toString(), is(a.id()));
        assertThat(jdbc.queryForObject("select count(*) from workout_sessions where user_id=?::uuid and title='Forged session'", Integer.class, b.id()), is(0));
        assertThat(jdbc.queryForObject("select count(*) from workout_templates where user_id=?::uuid and name='Forged template'", Integer.class, b.id()), is(0));
        assertThat(jdbc.queryForObject("select count(*) from meals where user_id=?::uuid and name='Forged meal'", Integer.class, b.id()), is(0));
    }



    @Test void compositeMealValidationReturnsStructured400AndNoRows() throws Exception {
        Session owner = registerSession("composite-validation-meal-");
        UUID food = UUID.randomUUID();
        jdbc.update("insert into foods(id,name,serving_size,calories,protein_g,carbs_g,fat_g,fiber_g) values (?,?,?,?,?,?,?,?)", food, "Validation food", 100, 100, 10, 10, 5, 2);
        Map<String, Integer> before = compositeCounts();
        List<Object> invalidPayloads = List.of(
                objectMap("meal", objectMap("meal_type", "LUNCH", "name", "Missing date", "source", "manual"), "items", List.of(objectMap("food_id", food, "grams", 100, "quantity", 1))),
                objectMap("meal", objectMap("meal_date", LocalDate.now(), "meal_type", "INVALID", "name", "Bad type", "source", "manual"), "items", List.of(objectMap("food_id", food, "grams", 100, "quantity", 1))),
                objectMap("meal", objectMap("meal_date", LocalDate.now(), "meal_type", "LUNCH", "name", "Bad UUID", "source", "manual"), "items", List.of(objectMap("food_id", "not-a-uuid", "grams", 100, "quantity", 1))),
                objectMap("meal", objectMap("meal_date", LocalDate.now(), "meal_type", "LUNCH", "name", "Empty items", "source", "manual"), "items", List.of()),
                objectMap("meal", objectMap("meal_date", LocalDate.now(), "meal_type", "LUNCH", "name", "Bad grams", "source", "manual"), "items", List.of(objectMap("food_id", food, "grams", 0, "quantity", 1))),
                objectMap("meal", objectMap("meal_date", LocalDate.now(), "meal_type", "LUNCH", "name", "Bad quantity", "source", "manual"), "items", List.of(objectMap("food_id", food, "grams", 100, "quantity", 0))));
        for (Object payload : invalidPayloads) assertStructuredBadRequest(postJson(owner, "/api/v1/meals/complete", payload));
        assertNoCompositeGrowth(before);
    }


    private MvcResult postJson(Session user, String path, Object payload) throws Exception {
        return mvc.perform(post(path).header("Authorization", "Bearer " + user.access())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(payload))).andReturn();
    }

    @Test void compositeSessionValidationReturnsStructured400AndNoRows() throws Exception {
        Session owner = registerSession("composite-validation-session-");
        UUID exercise = UUID.randomUUID();
        jdbc.update("insert into exercises(id,name) values (?,?)", exercise, "Validation exercise");
        Map<String, Integer> before = compositeCounts();
        List<Object> invalidPayloads = List.of(
                objectMap("session", objectMap("title", "Missing type", "duration_minutes", 20, "perceived_effort", 5, "completed", true), "exercises", List.of(objectMap("exercise_id", exercise, "order_index", 0, "sets", List.of(objectMap("set_number", 1, "reps", 8, "completed", true))))),
                objectMap("session", objectMap("title", "Bad UUID", "workout_type", "Strength", "duration_minutes", 20, "perceived_effort", 5, "completed", true), "exercises", List.of(objectMap("exercise_id", "not-a-uuid", "order_index", 0, "sets", List.of(objectMap("set_number", 1, "reps", 8, "completed", true))))),
                objectMap("session", objectMap("title", "Empty children", "workout_type", "Strength", "duration_minutes", 20, "perceived_effort", 5, "completed", true), "exercises", List.of()),
                objectMap("session", objectMap("title", "Bad set", "workout_type", "Strength", "duration_minutes", 20, "perceived_effort", 5, "completed", true), "exercises", List.of(objectMap("exercise_id", exercise, "order_index", 0, "sets", List.of(objectMap("set_number", 0, "reps", 8, "completed", true))))));
        for (Object payload : invalidPayloads) assertStructuredBadRequest(postJson(owner, "/api/v1/workout-sessions/complete", payload));
        assertNoCompositeGrowth(before);
    }

    @Test void compositeTemplateValidationReturnsStructured400AndNoRows() throws Exception {
        Session owner = registerSession("composite-validation-template-");
        UUID exercise = UUID.randomUUID();
        jdbc.update("insert into exercises(id,name) values (?,?)", exercise, "Validation exercise");
        Map<String, Integer> before = compositeCounts();
        List<Object> invalidPayloads = List.of(
                objectMap("template", objectMap("description", "Missing name", "workout_type", "Strength", "estimated_minutes", 20, "favorite", false), "exercises", List.of(objectMap("exercise_id", exercise, "order_index", 0, "target_sets", 3, "target_reps", "8-12"))),
                objectMap("template", objectMap("name", "Bad UUID", "workout_type", "Strength", "estimated_minutes", 20, "favorite", false), "exercises", List.of(objectMap("exercise_id", "not-a-uuid", "order_index", 0, "target_sets", 3, "target_reps", "8-12"))),
                objectMap("template", objectMap("name", "Empty children", "workout_type", "Strength", "estimated_minutes", 20, "favorite", false), "exercises", List.of()),
                objectMap("template", objectMap("name", "Bad child", "workout_type", "Strength", "estimated_minutes", 20, "favorite", false), "exercises", List.of(objectMap("exercise_id", exercise, "order_index", 0, "target_sets", 0, "target_reps", "8-12"))));

        for (Object payload : invalidPayloads) assertStructuredBadRequest(postJson(owner, "/api/v1/workout-templates/complete", payload));
        assertNoCompositeGrowth(before);
    }


    @Test void compositeForeignChildReferencesAreRejectedWithoutCrossUserMutation() throws Exception {
        Session a = registerSession("composite-foreign-a-");
        Session b = registerSession("composite-foreign-b-");
        UUID exercise = UUID.randomUUID();
        jdbc.update("insert into exercises(id,name) values (?,?)", exercise, "Catalog exercise");
        MvcResult sessionResult = postJson(b, "/api/v1/workout-sessions/complete", objectMap("session", objectMap("title", "B source", "workout_type", "Strength", "duration_minutes", 20, "perceived_effort", 6, "completed", true), "exercises", List.of(objectMap("exercise_id", exercise, "order_index", 0, "sets", List.of(objectMap("set_number", 1, "reps", 8, "completed", true))))));
        UUID sessionId = UUID.fromString(mapper.readTree(sessionResult.getResponse().getContentAsString()).path("id").asText());
        UUID sessionChild = jdbc.queryForObject("select id from workout_exercises where workout_session_id=?", UUID.class, sessionId);
        MvcResult templateResult = postJson(b, "/api/v1/workout-templates/complete", objectMap("template", objectMap("name", "B template", "workout_type", "Strength", "estimated_minutes", 20, "favorite", false), "exercises", List.of(objectMap("exercise_id", exercise, "order_index", 0, "target_sets", 3, "target_reps", "8-12"))));
        UUID templateId = UUID.fromString(mapper.readTree(templateResult.getResponse().getContentAsString()).path("id").asText());
        UUID templateChild = jdbc.queryForObject("select id from workout_template_exercises where template_id=?", UUID.class, templateId);
        UUID food = UUID.randomUUID();
        jdbc.update("insert into foods(id,name,serving_size,calories,protein_g,carbs_g,fat_g,fiber_g) values (?,?,?,?,?,?,?,?)", food, "Catalog food", 100, 100, 10, 10, 5, 2);
        MvcResult mealResult = postJson(b, "/api/v1/meals/complete", objectMap("meal", objectMap("meal_date", LocalDate.now(), "meal_type", "LUNCH", "name", "B meal", "source", "manual"), "items", List.of(objectMap("food_id", food, "grams", 100, "quantity", 1))));
        UUID mealId = UUID.fromString(mapper.readTree(mealResult.getResponse().getContentAsString()).path("id").asText());
        UUID mealChild = jdbc.queryForObject("select id from meal_items where meal_id=?", UUID.class, mealId);
        Map<String, Integer> before = compositeCounts();
        assertThat(postJson(a, "/api/v1/workout-sessions/complete", objectMap("session", objectMap("title", "A foreign", "workout_type", "Strength", "duration_minutes", 20, "perceived_effort", 6, "completed", true), "exercises", List.of(objectMap("exercise_id", sessionChild, "order_index", 0, "sets", List.of(objectMap("set_number", 1, "reps", 8, "completed", true)))))).getResponse().getStatus(), is(404));
        assertThat(postJson(a, "/api/v1/workout-templates/complete", objectMap("template", objectMap("name", "A foreign", "workout_type", "Strength", "estimated_minutes", 20, "favorite", false), "exercises", List.of(objectMap("exercise_id", templateChild, "order_index", 0, "target_sets", 3, "target_reps", "8-12")))).getResponse().getStatus(), is(404));
        assertThat(postJson(a, "/api/v1/meals/complete", objectMap("meal", objectMap("meal_date", LocalDate.now(), "meal_type", "LUNCH", "name", "A foreign", "source", "manual"), "items", List.of(objectMap("food_id", mealChild, "grams", 100, "quantity", 1)))).getResponse().getStatus(), is(404));
        assertNoCompositeGrowth(before);
        assertThat(jdbc.queryForObject("select count(*) from workout_sessions where id=? and user_id=?::uuid", Integer.class, sessionId, b.id()), is(1));
        assertThat(jdbc.queryForObject("select count(*) from workout_templates where id=? and user_id=?::uuid", Integer.class, templateId, b.id()), is(1));
        assertThat(jdbc.queryForObject("select count(*) from meals where id=? and user_id=?::uuid", Integer.class, mealId, b.id()), is(1));
    }

}
