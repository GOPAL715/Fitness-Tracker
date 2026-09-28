package com.fittrack.nutrition;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

/**
 * The one nutrition calculation contract in the system.
 *
 * <p>Every nutrition number the product shows is derived here, from the food catalog and a portion
 * weight. There is no second implementation, and no caller may pass a total in.
 *
 * <p><b>The basis is per 100 g.</b> The {@code foods} table stores calories and every macro per
 * 100 g, so a portion is simply
 *
 * <pre>portion = value_per_100g * grams / 100</pre>
 *
 * {@code serving_size} is display metadata (the label a catalog entry happens to use) and is
 * deliberately <em>not</em> a denominator. An earlier version divided by {@code serving_size} while
 * the browser divided by 100, so the same 200 g portion was stored as one number and displayed as
 * another whenever a food was not catalogued at exactly 100 g. With one basis there is nothing left
 * to disagree about.
 *
 * <p>Intermediate multiplication runs at extra precision and only the final result is rounded, so
 * summing many small items does not accumulate the error per-item rounding would introduce.
 */
@Service
public class NutritionCalculator {

    /** The weight the catalog's nutrition values are expressed per. */
    public static final BigDecimal BASIS_GRAMS = new BigDecimal("100");

    /** Scale of every persisted or returned total. */
    public static final int SCALE = 2;

    /** Guard digits kept during division, discarded by the final rounding. */
    private static final int GUARD_SCALE = SCALE + 6;

    private final JdbcTemplate jdbc;

    public NutritionCalculator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One food's nutrition, exactly as the catalog holds it: every value per 100 g.
     *
     * <p>A missing value is treated as zero rather than propagated, so a partially populated
     * catalog entry contributes what it does know instead of nulling the whole meal.
     */
    public record FoodNutrition(BigDecimal calories, BigDecimal proteinG, BigDecimal carbsG,
                                BigDecimal fatG, BigDecimal fiberG, BigDecimal sugarG,
                                BigDecimal sodiumMg) {}

    /** The nutrition of one portion of a food, rounded to {@link #SCALE}. */
    public record Portion(BigDecimal calories, BigDecimal proteinG, BigDecimal carbsG,
                          BigDecimal fatG, BigDecimal fiberG, BigDecimal sugarG,
                          BigDecimal sodiumMg) {

        public static Portion zero() {
            BigDecimal z = BigDecimal.ZERO.setScale(SCALE);
            return new Portion(z, z, z, z, z, z, z);
        }
    }

    /**
     * The single formula: a per-100 g value scaled to the requested portion weight.
     *
     * <p>Null inputs read as zero, so a food missing a macro yields zero for that macro rather than
     * a null that would poison every total it is added to.
     */
    public static BigDecimal portion(BigDecimal per100g, BigDecimal grams) {
        BigDecimal value = per100g == null ? BigDecimal.ZERO : per100g;
        BigDecimal weight = grams == null ? BigDecimal.ZERO : grams;
        return value.multiply(weight)
                .divide(BASIS_GRAMS, GUARD_SCALE, RoundingMode.HALF_UP)
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** The nutrition of a portion of the given food. */
    public static Portion forGrams(FoodNutrition food, BigDecimal grams) {
        if (food == null) return Portion.zero();
        return new Portion(
                portion(food.calories(), grams),
                portion(food.proteinG(), grams),
                portion(food.carbsG(), grams),
                portion(food.fatG(), grams),
                portion(food.fiberG(), grams),
                portion(food.sugarG(), grams),
                portion(food.sodiumMg(), grams));
    }

    /** Adds two portions. Both sides are already at {@link #SCALE}. */
    public static Portion add(Portion a, Portion b) {
        Portion left = a == null ? Portion.zero() : a;
        Portion right = b == null ? Portion.zero() : b;
        return new Portion(
                sum(left.calories(), right.calories()),
                sum(left.proteinG(), right.proteinG()),
                sum(left.carbsG(), right.carbsG()),
                sum(left.fatG(), right.fatG()),
                sum(left.fiberG(), right.fiberG()),
                sum(left.sugarG(), right.sugarG()),
                sum(left.sodiumMg(), right.sodiumMg()));
    }

    private static BigDecimal sum(BigDecimal a, BigDecimal b) {
        return nz(a).add(nz(b)).setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** Reads a catalog row's per-100 g values, or null when the food does not exist. */
    public FoodNutrition readFood(UUID foodId) {
        List<FoodNutrition> rows = jdbc.query(
                "SELECT calories, protein_g, carbs_g, fat_g, fiber_g, sugar_g, sodium_mg"
                        + " FROM foods WHERE id=CAST(? AS uuid)",
                (rs, n) -> new FoodNutrition(rs.getBigDecimal("calories"), rs.getBigDecimal("protein_g"),
                        rs.getBigDecimal("carbs_g"), rs.getBigDecimal("fat_g"),
                        rs.getBigDecimal("fiber_g"), rs.getBigDecimal("sugar_g"),
                        rs.getBigDecimal("sodium_mg")),
                foodId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Looks a food up by name through the indexed generated column, not a function on the row. */
    public UUID findFoodIdByName(String name) {
        if (name == null || name.isBlank()) return null;
        List<UUID> rows = jdbc.query(
                "SELECT id FROM foods WHERE name_normalized=lower(btrim(?)) LIMIT 1",
                (rs, n) -> rs.getObject("id", UUID.class), name);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Recomputes a meal's stored totals from the foods and portion weights of its items.
     *
     * <p>This is what makes a meal's macros trustworthy no matter which endpoint wrote the items:
     * the numbers on the meal row are always derived here rather than taken from a request body. An
     * item whose food no longer exists contributes zero rather than being silently attributed, and
     * the item rows are refreshed to agree with the meal so the two can never drift apart.
     */
    public Portion recomputeMeal(UUID mealId) {
        List<Object[]> items = jdbc.query(
                "SELECT mi.food_id, mi.grams, f.calories, f.protein_g, f.carbs_g, f.fat_g,"
                        + " f.fiber_g, f.sugar_g, f.sodium_mg"
                        + " FROM meal_items mi LEFT JOIN foods f ON f.id=mi.food_id"
                        + " WHERE mi.meal_id=CAST(? AS uuid)",
                (rs, n) -> new Object[]{
                        rs.getObject("food_id", UUID.class), rs.getBigDecimal("grams"),
                        rs.getBigDecimal("calories"), rs.getBigDecimal("protein_g"),
                        rs.getBigDecimal("carbs_g"), rs.getBigDecimal("fat_g"),
                        rs.getBigDecimal("fiber_g"), rs.getBigDecimal("sugar_g"),
                        rs.getBigDecimal("sodium_mg")},
                mealId);

        Portion total = Portion.zero();
        for (Object[] row : items) {
            UUID foodId = (UUID) row[0];
            BigDecimal grams = (BigDecimal) row[1];
            if (foodId == null) continue;
            Portion portion = forGrams(new FoodNutrition(
                    (BigDecimal) row[2], (BigDecimal) row[3], (BigDecimal) row[4],
                    (BigDecimal) row[5], (BigDecimal) row[6], (BigDecimal) row[7],
                    (BigDecimal) row[8]), grams);
            total = add(total, portion);
            jdbc.update("UPDATE meal_items SET calories=?,protein_g=?,carbs_g=?,fat_g=?,fiber_g=?"
                            + " WHERE meal_id=CAST(? AS uuid) AND food_id=CAST(? AS uuid)",
                    portion.calories(), portion.proteinG(), portion.carbsG(),
                    portion.fatG(), portion.fiberG(), mealId, foodId);
        }
        jdbc.update("UPDATE meals SET calories=?,protein_g=?,carbs_g=?,fat_g=?,fiber_g=?"
                        + " WHERE id=CAST(? AS uuid)",
                total.calories(), total.proteinG(), total.carbsG(), total.fatG(),
                total.fiberG(), mealId);
        return total;
    }
}
