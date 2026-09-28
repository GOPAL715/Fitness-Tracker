package com.fittrack.acceptance;

import com.fittrack.acceptance.support.AbstractAcceptanceTest;
import com.fittrack.ai.AiProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Phase 8: the scanner's nutrition, corrections and confirmation.
 *
 * <p>Fiber is pinned first because it was simply missing: the confirm insert omitted the column, so
 * every scanned meal reported zero fiber while the same food entered by hand reported its real
 * value. Also pinned: totals follow the one per-100g basis, a correction is addressed by item id,
 * confirmation happens once, and a scan is deleted together with its stored image.
 */
@SpringBootTest(classes = com.fittrack.api.FitTrackApplication.class)
@AutoConfigureMockMvc
class ScannerNutritionAcceptanceTest extends AbstractAcceptanceTest {

    @org.springframework.boot.test.mock.mockito.MockBean
    private AiProvider aiProvider;

    @org.springframework.beans.factory.annotation.Value("${app.storage-path}")
    String storagePath;

    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1};

    /** A fixture catalogued at 250 g: 4 g fiber per 100 g, so 200 g must store 8 g. */
    private UUID catalogFood(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into foods(id,name,category,serving_size,serving_unit,calories,protein_g,"
                        + "carbs_g,fat_g,fiber_g,sugar_g,sodium_mg,source) values (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, name, "Test", new BigDecimal("250"), "g",
                new BigDecimal("200"), new BigDecimal("10"), new BigDecimal("20"),
                new BigDecimal("5"), new BigDecimal("4"), new BigDecimal("1"), new BigDecimal("50"), "test");
        return id;
    }

    private MvcResult upload(Session user) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/v1/food-scans")
                .file(new MockMultipartFile("file", "meal.jpg", "image/jpeg", JPEG));
        return mvc.perform(request.header("Authorization", "Bearer " + user.access())).andReturn();
    }

    private void detect(String name, double grams, double confidence) {
        org.mockito.Mockito.doReturn(new AiProvider.FoodAnalysis("test-model",
                        List.of(new AiProvider.FoodItem(name, grams, confidence))))
                .when(aiProvider).analyzeFood(any(byte[].class), anyString());
    }

    private MvcResult postAs(Session user, String path, String body) throws Exception {
        return mvc.perform(post(path).header("Authorization", "Bearer " + user.access())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private String imageKeyOf(String scanId) {
        return jdbc.queryForObject("select image_path from food_scans where id=CAST(? as uuid)",
                String.class, UUID.fromString(scanId));
    }

    @Test
    @DisplayName("Phase 8 - a confirmed scan persists fiber along with the other macros")
    void confirmedScanPersistsFiber() throws Exception {
        Session user = register("p8-scan-fiber-");
        catalogFood("Scan Brown Rice");
        detect("Scan Brown Rice", 200, 0.9);

        String scanId = json(upload(user)).path("id").asText();
        assertStatus(postAs(user, "/api/v1/food-scans/" + scanId + "/confirm", "{}"), 200);
        UUID mealId = jdbc.queryForObject("select meal_id from food_scans where id=CAST(? as uuid)",
                UUID.class, UUID.fromString(scanId));

        // 4 g fiber per 100 g over 200 g is 8 g, on the item and folded into the meal.
        assertThat(new BigDecimal(jdbc.queryForObject("select fiber_g::text from meals where id=?",
                String.class, mealId))).isEqualByComparingTo("8.00");
        assertThat(new BigDecimal(jdbc.queryForObject("select fiber_g::text from meal_items where meal_id=?",
                String.class, mealId))).isEqualByComparingTo("8.00");
        // 200 kcal per 100 g over 200 g is 400, on the per-100g basis rather than the serving one.
        assertThat(new BigDecimal(jdbc.queryForObject("select calories::text from meals where id=?",
                String.class, mealId))).isEqualByComparingTo("400.00");
    }

    @Test
    @DisplayName("Phase 8 - a correction is addressed by item id and recomputes the item")
    void correctionByItemIdRecalculates() throws Exception {
        Session user = register("p8-scan-correct-");
        catalogFood("Scan Brown Rice");
        detect("Scan Brown Rice", 100, 0.9);

        MvcResult up = upload(user);
        String scanId = json(up).path("id").asText();
        String itemId = json(up).path("items").get(0).path("id").asText();

        MvcResult corrected = mvc.perform(put("/api/v1/food-scans/" + scanId + "/items")
                .header("Authorization", "Bearer " + user.access())
                .contentType(MediaType.APPLICATION_JSON)
                .content("[{\"itemId\":\"" + itemId + "\",\"name\":\"Scan Brown Rice\",\"grams\":250}]"))
                .andReturn();
        assertStatus(corrected, 200);
        // 4 g fiber per 100 g over 250 g is 10 g, recomputed rather than scaled from the old value.
        assertThat(new BigDecimal(json(corrected).path("items").get(0).path("fiberG").asText()))
                .isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("Phase 8 - a scan can only be confirmed once")
    void duplicateConfirmationIsAConflict() throws Exception {
        Session user = register("p8-scan-double-");
        catalogFood("Scan Brown Rice");
        detect("Scan Brown Rice", 100, 0.9);
        String scanId = json(upload(user)).path("id").asText();

        assertStatus(postAs(user, "/api/v1/food-scans/" + scanId + "/confirm", "{}"), 200);
        assertThat(postAs(user, "/api/v1/food-scans/" + scanId + "/confirm", "{}")
                .getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    @DisplayName("Phase 8 - another account cannot read, confirm or delete a scan")
    void scansAreOwnerScoped() throws Exception {
        Session owner = register("p8-scan-owner-");
        Session other = register("p8-scan-other-");
        catalogFood("Scan Brown Rice");
        detect("Scan Brown Rice", 100, 0.9);
        String scanId = json(upload(owner)).path("id").asText();

        assertStatus(getAs(other, "/api/v1/food-scans/" + scanId), 404);
        assertThat(postAs(other, "/api/v1/food-scans/" + scanId + "/confirm", "{}")
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(delete("/api/v1/food-scans/" + scanId)
                .header("Authorization", "Bearer " + other.access())).andReturn()
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("Phase 8 - a scan's image path cannot be written through the generic resource API")
    void imagePathIsServerControlled() throws Exception {
        Session user = register("p8-scan-image-");
        catalogFood("Scan Brown Rice");
        detect("Scan Brown Rice", 100, 0.9);
        String scanId = json(upload(user)).path("id").asText();
        String original = imageKeyOf(scanId);

        // The generic route no longer offers food-scans for mutation at all, so image_path, status,
        // model and error are reached only through the scanner endpoints that own them.
        assertThat(mvc.perform(MockMvcRequestBuilders.put("/api/v1/food-scans/" + scanId)
                .header("Authorization", "Bearer " + user.access())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"image_path\":\"" + user.id() + "/someone-elses-object\"}"))
                .andReturn().getResponse().getStatus()).isEqualTo(404);

        assertThat(imageKeyOf(scanId)).isEqualTo(original);
    }

    @Test
    @DisplayName("Phase 8 - deleting a scan removes its stored image too")
    void deletingAScanRemovesItsImage() throws Exception {
        Session user = register("p8-scan-cleanup-");
        catalogFood("Scan Brown Rice");
        detect("Scan Brown Rice", 100, 0.9);
        String scanId = json(upload(user)).path("id").asText();
        String key = imageKeyOf(scanId);

        Predicate<Path> exists = p -> {
            try (var stream = Files.walk(p)) {
                return stream.anyMatch(x -> x.toString().replace('\\', '/').endsWith(key));
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        };
        Path root = Path.of(storagePath).toAbsolutePath();
        Path backendRelative = Path.of("backend").resolve(storagePath).toAbsolutePath();
        if (!Files.exists(root) && Files.exists(backendRelative)) root = backendRelative;
        final Path storageRoot = root;
        assertThat(exists.test(storageRoot)).as("the image is stored before the delete").isTrue();

        assertStatus(mvc.perform(delete("/api/v1/food-scans/" + scanId)
                .header("Authorization", "Bearer " + user.access())).andReturn(), 204);

        assertThat(exists.test(storageRoot)).as("the image must not outlive the scan").isFalse();
    }

    @Test
    @DisplayName("Phase 8 - a detection that is not in the catalog contributes no nutrition")
    void unmatchedFoodContributesNothing() throws Exception {
        Session user = register("p8-scan-unmatched-");
        detect("Something Not Catalogued", 200, 0.4);

        MvcResult view = getAs(user, "/api/v1/food-scans/" + json(upload(user)).path("id").asText());
        assertStatus(view, 200);
        assertThat(json(view).path("items").get(0).path("foodId").isNull()).isTrue();
        assertThat(new BigDecimal(json(view).path("items").get(0).path("calories").asText()))
                .isEqualByComparingTo("0.00");
    }

}
