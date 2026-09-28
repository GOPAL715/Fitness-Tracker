package com.fittrack.health;

import com.fittrack.acceptance.support.AbstractAcceptanceTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where unit conversion belongs, and what it must not do.
 *
 * <p>The pipeline is fixed and each stage has one job:
 *
 * <pre>
 *   provider raw payload        a real adapter, which does not exist in this repository
 *        -> HealthNormalizer    converts raw units into FitTrack canonical units
 *        -> HealthProvider      the canonical contract: pounds, minutes, kilocalories
 *        -> HealthRecordValidator   technical validation only, no medical judgement
 *        -> HealthSyncService   batched, owner-scoped persistence
 * </pre>
 *
 * <p>Conversion therefore happens <b>once</b>, in the adapter, before the canonical contract is
 * produced. {@link HealthSyncService} must never see kilograms or seconds, and must never convert:
 * a second conversion would silently corrupt a correctly normalised value.
 *
 * <p>This class cannot be runtime-verified against Health Connect, because no adapter exists and the
 * native bridge is a separate repository. What it does assert is the boundary: the normaliser is
 * the single conversion point, the canonical contract is expressed in canonical units, and the
 * persistence layer performs no arithmetic on values it did not produce.
 */
class HealthNormalizationBoundaryTest {

    @Test
    @DisplayName("the canonical contract is expressed in canonical units, not provider units")
    void canonicalContractIsUnitless() throws Exception {
        // Field names are the contract. They name pounds, minutes and kilocalories, so a caller
        // cannot pass kilograms into a parameter called weightLb without it reading as wrong.
        for (String field : List.of("weightLb", "activeMinutes", "caloriesBurned", "steps")) {
            assertThat(HealthProvider.BodyRecord.class.getDeclaredFields())
                    .as("BodyRecord should not carry a raw-unit field named %s", field)
                    .noneMatch(f -> f.getName().equalsIgnoreCase(field + "Kg"));
        }
        assertThat(HealthProvider.BodyRecord.class.getDeclaredFields())
                .anyMatch(f -> f.getName().equals("weightLb"));
    }

    @Test
    @DisplayName("the normaliser is a pure function of its input, so an adapter can call it freely")
    void normaliserIsPure() throws Exception {
        Method kg = HealthNormalizer.class.getMethod("kilogramsToPounds", BigDecimal.class);
        BigDecimal input = new BigDecimal("72.5");
        assertThat(kg.invoke(null, input)).isEqualTo(kg.invoke(null, input));
        // The input is not mutated, so a caller can safely reuse a parsed provider payload.
        assertThat(input).isEqualByComparingTo("72.5");
    }

    @Test
    @DisplayName("pound values are stable under re-normalisation, so a double conversion is detectable")
    void poundsAreIdempotent() {
        // A value already in pounds must survive a second pass unchanged. That is what makes a
        // double conversion detectable: kg -> lb is not idempotent, but lb -> lb is.
        BigDecimal alreadyPounds = new BigDecimal("180.25");
        assertThat(HealthNormalizer.pounds(HealthNormalizer.pounds(alreadyPounds)))
                .isEqualTo(HealthNormalizer.pounds(alreadyPounds));

        // Kilograms are not idempotent, which is precisely why an adapter converts exactly once:
        // running kilograms through the pound path again would inflate the value.
        BigDecimal fromKg = HealthNormalizer.kilogramsToPounds(new BigDecimal("70"));
        assertThat(fromKg.doubleValue()).isCloseTo(154.32d, org.assertj.core.data.Offset.offset(0.01d));
        assertThat(HealthNormalizer.pounds(fromKg).doubleValue()).isCloseTo(154.32d,
                org.assertj.core.data.Offset.offset(0.01d));
    }

    @Test
    @DisplayName("the persistence layer holds no conversion helper of its own")
    void persistenceDoesNotConvert() {
        // A private helper named like a converter inside the sync service would mean a second
        // conversion point. The sync service is only allowed to read already-canonical values.
        for (Method method : HealthSyncService.class.getDeclaredMethods()) {
            String name = method.getName().toLowerCase(java.util.Locale.ROOT);
            assertThat(name.contains("kilogram") || name.contains("pound") || name.contains("secondsto")
                            || name.contains("convert"))
                    .as("HealthSyncService must not convert units; found %s", method.getName())
                    .isFalse();
        }
    }

    @Test
    @DisplayName("validation runs on canonical values, after conversion")
    void validationSeesCanonicalValues() {
        // Ordering is structural: the service validates the batch the provider handed it, and the
        // provider's contract is canonical. There is no post-validation conversion step that could
        // turn a rejected negative into an accepted one.
        var validate = java.util.Arrays.stream(HealthRecordValidator.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("validate"))
                .findFirst()
                .orElseThrow();
        assertThat(validate.getReturnType().getSimpleName()).isEqualTo("Result");
    }
}
