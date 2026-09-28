package com.fittrack.acceptance;

import com.fittrack.acceptance.support.AbstractAcceptanceTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Phase 8: the nutrition contract and the canonical meal write path.
 *
 * <p>The catalog holds every macro per 100 g and that is now the single basis. It used to be the
 * only thing the browser used while the server divided by the food's serving size, so the same
 * portion was stored as one number and displayed as another. These pin the one formula, the fact
 * that a client cannot dictate a total, and that an unknown food is a 404 rather than a silent
 * zero.
 *
 * <p>The foods here are fixtures written by the test rather than catalog contents: the shipped
 * catalog is deliberately empty until an approved nutrition dataset is supplied.
 */
@SpringBootTest(classes = com.fittrack.api.FitTrackApplication.class)
@AutoConfigureMockMvc
class NutritionContractAcceptanceTest extends AbstractAcceptanceTest {

    /** A fixture catalogued at 250 g, so serving_size cannot accidentally look like the basis. */
    private UUID catalogFood(String name, int per100gCalories) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into foods(id,name,category,serving_size,serving_unit,calories,protein_g,"
                        + "carbs_g,fat_g,fiber_g,sugar_g,sodium_mg,source) values (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, name, "Test", new BigDecimal("250"), "g",
                new BigDecimal(per100gCalories), new BigDecimal("10"), new BigDecimal("20"),
                new BigDecimal("5"), new BigDecimal("2"), new BigDecimal("1"), new BigDecimal("50"), "test");
        return id;
    }

    private MvcResult completeMeal(Session user, UUID foodId, String grams) throws Exception {
        return postJson(user, "/api/v1/meals/complete", mealJson(foodId, grams));
    }

    private String mealJson(UUID foodId, String grams) {
        return "{\"meal\":{\"meal_date\":\"" + LocalDate.now() + "\",\"meal_type\":\"LUNCH\","
                + "\"name\":\"Contract meal\"},\"items\":[{\"food_id\":\"" + foodId
                + "\",\"grams\":" + grams + ",\"quantity\":1}]}";
    }

    private BigDecimal mealTotal(UUID mealId, String column) {
        return new BigDecimal(jdbc.queryForObject(
                "select " + column + "::text from meals where id=CAST(? as uuid)", String.class, mealId));
    }

    private UUID mealIdOf(MvcResult result) throws Exception {
        return UUID.fromString(json(result).path("id").asText());
    }

    /** Posts a JSON body as the given session, so the tests read as one call per scenario. */
    private MvcResult postJson(Session user, String path, String body) throws Exception {
        return mvc.perform(post(path).header("Authorization", "Bearer " + user.access())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    /** A rejected request is a structured 400, not an opaque server error. */
    private void assertStructuredBadRequest(MvcResult result) throws Exception {
        assertStructuredError(result, 400, "Bad Request");
    }

    /* ---------- the single per-100g formula (M1) ---------- */

    @Test
    @DisplayName("Phase 8 - 250 g of a food carrying 550 kcal per 100 g stores 1375 kcal")
    void perHundredGramBasisIsTheOnlyBasis() throws Exception {
        Session user = register("p8-basis-");
        // 550 kcal per 100 g over 250 g is 1375. Dividing by the food's 250 g serving size instead
        // would have stored 550, which is exactly the divergence this pins.
        UUID food = catalogFood("Contract Cheeseburger", 550);
        UUID mealId = mealIdOf(completeMeal(user, food, "250"));

        assertThat(mealTotal(mealId, "calories")).isEqualByComparingTo("1375.00");
        // Every macro follows the same basis: 10 g protein per 100 g over 250 g is 25.
        assertThat(mealTotal(mealId, "protein_g")).isEqualByComparingTo("25.00");
        assertThat(mealTotal(mealId, "carbs_g")).isEqualByComparingTo("50.00");
        assertThat(mealTotal(mealId, "fat_g")).isEqualByComparingTo("12.50");
        assertThat(mealTotal(mealId, "fiber_g")).isEqualByComparingTo("5.00");
    }

    @Test
    @DisplayName("Phase 8 - the portion weight scales the per-100g value linearly")
    void portionWeightScalesLinearly() throws Exception {
        Session user = register("p8-scale-");
        UUID food = catalogFood("Contract Rice", 130);

        for (String grams : List.of("100", "50", "250", "1", "5000")) {
            UUID mealId = mealIdOf(completeMeal(user, food, grams));
            BigDecimal expected = new BigDecimal("130").multiply(new BigDecimal(grams))
                    .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
            assertThat(mealTotal(mealId, "calories"))
                    .as("%s g of 130 kcal per 100 g", grams)
                    .isEqualByComparingTo(expected);
        }
    }

    @Test
    @DisplayName("Phase 8 - a meal of several foods sums their portions")
    void severalItemsAreSummed() throws Exception {
        Session user = register("p8-sum-");
        UUID rice = catalogFood("Contract Rice", 130);
        UUID dal = catalogFood("Contract Dal", 100);
        String body = "{\"meal\":{\"meal_date\":\"" + LocalDate.now() + "\",\"meal_type\":\"DINNER\","
                + "\"name\":\"Rice and dal\"},\"items\":["
                + "{\"food_id\":\"" + rice + "\",\"grams\":200,\"quantity\":1},"
                + "{\"food_id\":\"" + dal + "\",\"grams\":150,\"quantity\":1}]}";
        UUID mealId = mealIdOf(postJson(user, "/api/v1/meals/complete", body));
        // 260 + 150
        assertThat(mealTotal(mealId, "calories")).isEqualByComparingTo("410.00");
        assertThat(mealTotal(mealId, "fiber_g")).isEqualByComparingTo("7.00");
    }

    /* ---------- validation ---------- */

    @Test
    @DisplayName("Phase 8 - an unknown food is a 404 and writes nothing")
    void unknownFoodIsNotFound() throws Exception {
        Session user = register("p8-unknown-");
        assertThat(completeMeal(user, UUID.randomUUID(), "100").getResponse().getStatus()).isEqualTo(404);
        assertThat(jdbc.queryForObject("select count(*) from meals where user_id=CAST(? as uuid)",
                Integer.class, user.id())).isZero();
    }

    @Test
    @DisplayName("Phase 8 - a portion weight outside 1..5000 g is a 400")
    void invalidGramsAreRejected() throws Exception {
        Session user = register("p8-grams-");
        UUID food = catalogFood("Contract Food", 100);
        for (String grams : List.of("0", "-5", "5001")) {
            assertStructuredBadRequest(completeMeal(user, food, grams));
        }
        assertThat(jdbc.queryForObject("select count(*) from meals where user_id=CAST(? as uuid)",
                Integer.class, user.id())).isZero();
    }

    @Test
    @DisplayName("Phase 8 - a meal needs a type, a name and at least one item")
    void malformedMealsAreRejected() throws Exception {
        Session user = register("p8-shape-");
        UUID food = catalogFood("Contract Food", 100);
        String day = LocalDate.now().toString();
        for (String body : List.of(
                "{\"meal\":{\"meal_date\":\"" + day + "\",\"name\":\"No type\"},\"items\":[{\"food_id\":\"" + food + "\",\"grams\":100,\"quantity\":1}]}",
                "{\"meal\":{\"meal_date\":\"" + day + "\",\"meal_type\":\"LUNCH\"},\"items\":[{\"food_id\":\"" + food + "\",\"grams\":100,\"quantity\":1}]}",
                "{\"meal\":{\"meal_date\":\"" + day + "\",\"meal_type\":\"LUNCH\",\"name\":\"Empty\"},\"items\":[]}",
                "{\"meal\":{\"meal_date\":\"" + day + "\",\"meal_type\":\"SUPPER\",\"name\":\"Bad type\"},\"items\":[{\"food_id\":\"" + food + "\",\"grams\":100,\"quantity\":1}]}")) {
            assertStructuredBadRequest(postJson(user, "/api/v1/meals/complete", body));
        }
    }

    /* ---------- the client cannot author a total ---------- */

    @Test
    @DisplayName("Phase 8 - a meal written through the generic API cannot state its own macros")
    void genericMealWriteCannotSetTotals() throws Exception {
        Session user = register("p8-generic-");
        UUID food = catalogFood("Contract Food", 200);

        MvcResult created = postJson(user, "/api/v1/meals",
                "{\"meal_date\":\"" + LocalDate.now() + "\",\"meal_type\":\"LUNCH\",\"name\":\"Generic\","
                        + "\"calories\":9999}");
        // The generic API refuses a field it does not accept, naming it. A client cannot offer a
        // total at all, which is stronger than accepting one and ignoring it.
        assertStructuredBadRequest(created);
        assertThat(json(created).path("message").asText()).contains("calories");
        assertThat(jdbc.queryForObject("select count(*) from meals where user_id=CAST(? as uuid)",
                Integer.class, user.id())).isZero();

        // The same request without the macro is accepted, and the meal starts at a real zero.
        UUID mealId = mealIdOf(postJson(user, "/api/v1/meals",
                "{\"meal_date\":\"" + LocalDate.now() + "\",\"meal_type\":\"LUNCH\",\"name\":\"Generic\"}"));
        assertThat(mealTotal(mealId, "calories")).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Phase 8 - meal items are written only through the canonical meal and scan endpoints")
    void mealItemsHaveNoGenericWritePath() throws Exception {
        Session user = register("p8-items-");
        UUID food = catalogFood("Contract Food", 200);
        UUID mealId = mealIdOf(postJson(user, "/api/v1/meals",
                "{\"meal_date\":\"" + LocalDate.now() + "\",\"meal_type\":\"LUNCH\",\"name\":\"Generic\"}"));

        // meal-items is a read-only collection in the generic API: a caller cannot inject an item
        // with totals of its own, and a client-supplied item was a silent 404 rather than a write.
        String item = "{\"meal_id\":\"" + mealId + "\",\"food_id\":\"" + food + "\",\"grams\":100,\"quantity\":1}";
        assertStatus(postJson(user, "/api/v1/meal-items", item), 404);
        assertThat(jdbc.queryForObject("select count(*) from meal_items where meal_id=CAST(? as uuid)",
                Integer.class, mealId)).isZero();

        // The canonical path still writes the item and derives its nutrition.
        UUID viaComposite = mealIdOf(completeMeal(user, food, "100"));
        assertThat(mealTotal(viaComposite, "calories")).isEqualByComparingTo("200.00");
        assertThat(jdbc.queryForObject("select count(*) from meal_items where meal_id=CAST(? as uuid)",
                Integer.class, viaComposite)).isEqualTo(1);
    }

    @Test
    @DisplayName("Phase 8 - editing a meal's descriptive fields cannot disturb its derived totals")
    void editingAMealCannotDisturbItsTotals() throws Exception {
        Session user = register("p8-recompute-");
        UUID food = catalogFood("Contract Food", 200);
        UUID mealId = mealIdOf(completeMeal(user, food, "100"));
        assertThat(mealTotal(mealId, "calories")).isEqualByComparingTo("200.00");

        // A rename is a legitimate partial update and leaves the derived totals alone.
        assertStatus(call(user, put("/api/v1/meals/" + mealId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Renamed\"}")), 200);
        assertThat(mealTotal(mealId, "calories")).isEqualByComparingTo("200.00");
        // Offering a total on an update is refused outright, so a partial patch cannot assert one.
        MvcResult attempted = call(user, put("/api/v1/meals/" + mealId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"calories\":9999}"));
        assertStructuredBadRequest(attempted);
        assertThat(json(attempted).path("message").asText()).contains("calories");
        assertThat(jdbc.queryForObject("select name from meals where id=CAST(? as uuid)",
                String.class, mealId)).isEqualTo("Renamed");
    }

    /* ---------- ownership and isolation ---------- */

    @Test
    @DisplayName("Phase 8 - a forged user_id cannot put a meal in another account")
    void forgedUserIdIsIgnored() throws Exception {
        Session owner = register("p8-owner-");
        Session attacker = register("p8-attacker-");
        UUID food = catalogFood("Contract Food", 100);
        String body = "{\"user_id\":\"" + owner.id() + "\",\"meal\":{\"meal_date\":\"" + LocalDate.now()
                + "\",\"meal_type\":\"LUNCH\",\"name\":\"Forged\"},\"items\":[{\"food_id\":\"" + food
                + "\",\"grams\":100,\"quantity\":1}]}";
        assertStatus(postJson(attacker, "/api/v1/meals/complete", body), 200);
        assertThat(jdbc.queryForObject("select count(*) from meals where user_id=CAST(? as uuid)",
                Integer.class, owner.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from meals where user_id=CAST(? as uuid)",
                Integer.class, attacker.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("Phase 8 - one account's meals and items are invisible to another")
    void crossUserNutritionIsIsolated() throws Exception {
        Session owner = register("p8-iso-owner-");
        Session other = register("p8-iso-other-");
        UUID food = catalogFood("Contract Food", 100);
        UUID mealId = mealIdOf(completeMeal(owner, food, "100"));
        String itemId = jdbc.queryForObject("select id::text from meal_items where meal_id=CAST(? as uuid)",
                String.class, mealId);

        assertThat(getAs(other, "/api/v1/meals/" + mealId).getResponse().getStatus()).isIn(403, 404);
        assertThat(getAs(other, "/api/v1/meal-items/" + itemId).getResponse().getStatus()).isIn(403, 404);
        assertThat(call(other, delete("/api/v1/meals/" + mealId)).getResponse().getStatus()).isIn(403, 404);
        assertThat(getAs(other, "/api/v1/meal-items").getResponse().getContentAsString())
                .doesNotContain("Contract meal");
    }

    @Test
    @DisplayName("Phase 8 - the nutrition endpoints reject an unauthenticated caller")
    void nutritionRequiresAuthentication() throws Exception {
        assertUnauthenticated(get("/api/v1/meals"));
        assertUnauthenticated(get("/api/v1/foods"));
        assertUnauthenticated(post("/api/v1/meals/complete")
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
    }

    @Test
    @DisplayName("Phase 8 - the shared food catalog cannot be written through the generic API")
    void catalogIsReadOnly() throws Exception {
        Session user = register("p8-catalog-");
        assertStatus(postJson(user, "/api/v1/foods",
                "{\"name\":\"Injected\",\"calories\":1}"), 403);
        assertThat(jdbc.queryForObject("select count(*) from foods where name='Injected'", Integer.class)).isZero();
    }



}
