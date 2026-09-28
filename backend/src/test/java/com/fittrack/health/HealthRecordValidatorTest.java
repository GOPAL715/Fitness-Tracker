package com.fittrack.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the technical validation layer.
 *
 * <p>Two things are deliberately absent. There are no medical thresholds: a body-fat or HRV value
 * that looks unusual is the user's real data, and rejecting it here would discard it on the basis of
 * a number FitTrack has no basis to judge. And a rejected record is never fatal, because one bad row
 * from a provider must not fail an entire sync.
 */
class HealthRecordValidatorTest {

    private static final LocalDate TODAY = LocalDate.now(java.time.ZoneOffset.UTC);
    private static final LocalDate PAST = TODAY.minusDays(1);

    private static HealthProvider.ActivityRecord activity(String id, LocalDate date, int steps,
                                                         long minutes, long calories) {
        return new HealthProvider.ActivityRecord(id, date, steps, minutes, calories);
    }

    private static HealthProvider.BodyRecord body(String id, LocalDate date, String weight, String fat) {
        return new HealthProvider.BodyRecord(id, date,
                weight == null ? null : new BigDecimal(weight),
                fat == null ? null : new BigDecimal(fat));
    }

    @Test
    @DisplayName("a well-formed record is accepted")
    void acceptsValidRecord() {
        var r = HealthRecordValidator.validate(
                List.of(activity("a1", PAST, 8000, 45, 2200)), List.of());
        assertThat(r.rejected()).isZero();
        assertThat(r.activity()).hasSize(1);
    }

    @Test
    @DisplayName("today's data is accepted; a provider may legitimately report the current day")
    void acceptsToday() {
        var r = HealthRecordValidator.validate(List.of(activity("a1", TODAY, 100, 5, 50)), List.of());
        assertThat(r.rejected()).isZero();
    }

    @Test
    @DisplayName("a null body measurement is not itself invalid")
    void acceptsNullBodyMeasurement() {
        // Absent weight is "not measured", which is legitimate; only an impossible value is a fault.
        var r = HealthRecordValidator.validate(List.of(), List.of(body("b1", PAST, null, "18")));
        assertThat(r.rejected()).isZero();
    }

    @Test
    @DisplayName("a missing record id is rejected, because it cannot be de-duplicated")
    void rejectsMissingRecordId() {
        var r = HealthRecordValidator.validate(List.of(activity("  ", PAST, 1, 1, 1)), List.of());
        assertThat(r.rejected()).isEqualTo(1);
        assertThat(r.reasons()).contains(HealthRecordValidator.MISSING_RECORD_ID);
    }

    @Test
    @DisplayName("a null date is rejected")
    void rejectsNullDate() {
        var r = HealthRecordValidator.validate(List.of(activity("a1", null, 1, 1, 1)), List.of());
        assertThat(r.reasons()).contains(HealthRecordValidator.INVALID_DATE);
    }

    @Test
    @DisplayName("a future date is rejected")
    void rejectsFutureDate() {
        var r = HealthRecordValidator.validate(
                List.of(activity("a1", TODAY.plusDays(1), 1, 1, 1)), List.of());
        assertThat(r.reasons()).contains(HealthRecordValidator.FUTURE_DATE);
    }

    @Test
    @DisplayName("negative counts are rejected")
    void rejectsNegativeActivity() {
        var r = HealthRecordValidator.validate(
                List.of(activity("a1", PAST, -5, 10, 100)), List.of());
        assertThat(r.reasons()).contains(HealthRecordValidator.NEGATIVE_VALUE);
    }

    @Test
    @DisplayName("negative active minutes are rejected")
    void rejectsNegativeActiveMinutes() {
        var r = HealthRecordValidator.validate(
                List.of(activity("a1", PAST, 100, -1, 100)), List.of());
        assertThat(r.reasons()).contains(HealthRecordValidator.NEGATIVE_VALUE);
    }

    @Test
    @DisplayName("zero and negative weight are rejected")
    void rejectsInvalidWeight() {
        assertThat(HealthRecordValidator.validate(List.of(),
                List.of(body("b1", PAST, "0", "18"))).reasons())
                .contains(HealthRecordValidator.INVALID_WEIGHT);
        assertThat(HealthRecordValidator.validate(List.of(),
                List.of(body("b2", PAST, "-10", "18"))).reasons())
                .contains(HealthRecordValidator.INVALID_WEIGHT);
    }

    @Test
    @DisplayName("more active minutes than a day contains is structurally impossible")
    void rejectsExcessiveActiveMinutes() {
        var r = HealthRecordValidator.validate(
                List.of(activity("a1", PAST, 100, 2000, 100)), List.of());
        assertThat(r.reasons()).contains(HealthRecordValidator.EXCESSIVE_VALUE);
    }

    @Test
    @DisplayName("a body-fat percentage above 100 is rejected as corrupt, not as a medical claim")
    void rejectsImpossibleBodyFatPercent() {
        var r = HealthRecordValidator.validate(List.of(), List.of(body("b1", PAST, "180", "150")));
        assertThat(r.reasons()).contains(HealthRecordValidator.EXCESSIVE_VALUE);
    }

    @Test
    @DisplayName("unusual but structurally valid values are kept, because there are no medical thresholds")
    void doesNotApplyMedicalThresholds() {
        // Low steps, high body fat, very low weight: none is a technical fault, and discarding a
        // user's real measurement would be a clinical judgement FitTrack must not make.
        var r = HealthRecordValidator.validate(
                List.of(activity("a1", PAST, 12, 1, 5)),
                List.of(body("b1", PAST, "1.0", "65")));
        assertThat(r.rejected()).isZero();
        assertThat(r.activity()).hasSize(1);
        assertThat(r.body()).hasSize(1);
    }

    @Test
    @DisplayName("one bad record does not discard the good ones in the same batch")
    void keepsGoodRecordsWhenOthersFail() {
        var r = HealthRecordValidator.validate(
                List.of(activity("good", PAST, 8000, 30, 2000),
                        activity(null, PAST, 1, 1, 1),
                        activity("good2", PAST, 9000, 40, 2100)),
                List.of());
        assertThat(r.activity()).hasSize(2);
        assertThat(r.rejected()).isEqualTo(1);
    }

    @Test
    @DisplayName("a null element in the batch is skipped rather than throwing")
    void toleratesNullElements() {
        var r = HealthRecordValidator.validate(
                java.util.Arrays.asList(activity("a1", PAST, 1, 1, 1), null), List.of());
        assertThat(r.rejected()).isEqualTo(1);
        assertThat(r.activity()).hasSize(1);
    }

    @Test
    @DisplayName("a record id that cannot fit the column is treated as absent")
    void recordIdMustFitColumn() {
        assertThat(HealthRecordValidator.usableRecordId("x".repeat(120))).isNotNull();
        assertThat(HealthRecordValidator.usableRecordId("x".repeat(121))).isNull();
        assertThat(HealthRecordValidator.usableRecordId("  ")).isNull();
    }

    @Test
    @DisplayName("an over-long cursor is treated as absent rather than truncated")
    void cursorMustFitColumn() {
        assertThat(HealthRecordValidator.usableCursor("offset:5")).isEqualTo("offset:5");
        assertThat(HealthRecordValidator.usableCursor("c".repeat(161))).isNull();
        assertThat(HealthRecordValidator.usableCursor("  ")).isNull();
    }

    @Test
    @DisplayName("ownership comparison is exact")
    void ownershipCheck() {
        var a = java.util.UUID.randomUUID();
        assertThat(HealthRecordValidator.isSameOwner(a, a)).isTrue();
        assertThat(HealthRecordValidator.isSameOwner(a, java.util.UUID.randomUUID())).isFalse();
        assertThat(HealthRecordValidator.isSameOwner(a, null)).isFalse();
        assertThat(HealthRecordValidator.isSameOwner(null, a)).isFalse();
    }
}
