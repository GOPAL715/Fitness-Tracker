package com.fittrack.health;

import com.fittrack.health.HealthConnectBatch.HealthConnectRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Inbound record validation (D8).
 *
 * <p>Two properties matter most. First, a bad record is dropped <b>on its own</b> so one malformed row
 * cannot fail a batch. Second, the two ways of getting a value wrong in opposite directions - a bridge
 * that already converted its weight, and one that invented an interval for a type the platform only
 * ever reports instantaneously - are both refused, because both would corrupt stored data silently
 * rather than loudly.
 */
class HealthConnectIngestValidatorTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 10);
    private static final LocalDate EARLIEST = TODAY.minusDays(29);

    private static Instant iso(String value) {
        return Instant.parse(value);
    }

    private static HealthConnectRecord steps(String id, String start, String end, String value, String unit) {
        return new HealthConnectRecord(id, "steps", iso(start), iso(end), new BigDecimal(value), unit);
    }

    @Test
    @DisplayName("a well-formed steps record is accepted and keeps its native unit")
    void acceptsWellFormedSteps() {
        var result = HealthConnectIngestValidator.validate(
                steps("r1", "2026-03-10T02:30:00Z", "2026-03-10T03:30:00Z", "500", "count"),
                ZONE, TODAY, EARLIEST);

        assertThat(result).isNotNull();
        assertThat(result.type()).isEqualTo(HealthConnectRecordType.STEPS);
        assertThat(result.recordId()).isEqualTo("r1");
        assertThat(result.value()).isEqualByComparingTo("500");
        assertThat(result.startDay()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("a weight record must arrive in kilograms and is not pre-converted")
    void requiresNativeWeightUnit() {
        // Health Connect reports kilograms. A bridge that already converted to pounds would otherwise be
        // stored as pounds and converted again, roughly quadrupling the user's weight.
        var rejected = HealthConnectIngestValidator.validate(
                new HealthConnectRecord("w1", "weight", iso("2026-03-10T05:00:00Z"),
                        iso("2026-03-10T05:00:00Z"), new BigDecimal("154.32"), "lb"),
                ZONE, TODAY, EARLIEST);
        assertThat(rejected).isNull();

        var accepted = HealthConnectIngestValidator.validate(
                new HealthConnectRecord("w1", "weight", iso("2026-03-10T05:00:00Z"),
                        iso("2026-03-10T05:00:00Z"), new BigDecimal("70"), "kg"),
                ZONE, TODAY, EARLIEST);
        assertThat(accepted).isNotNull();
    }

    @Test
    @DisplayName("an instantaneous record must be a point in time, not an interval")
    void rejectsIntervalShapedInstantRecords() {
        // Health Connect never aggregates weight to a total, so an interval here is a client bug that
        // would otherwise be summed into nonsense.
        var result = HealthConnectIngestValidator.validate(
                new HealthConnectRecord("w1", "weight", iso("2026-03-10T05:00:00Z"),
                        iso("2026-03-10T09:00:00Z"), new BigDecimal("70"), "kg"),
                ZONE, TODAY, EARLIEST);

        assertThat(result).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = { "sleep_session", "exercise_session", "distance", "total_calories",
            "heart_rate", "hrv", "resting_heart_rate", "readiness", "stress_level", "hydration" })
    @DisplayName("every type outside the D2 scope is refused by name rather than silently dropped")
    void refusesOutOfScopeTypes(String type) {
        var result = HealthConnectIngestValidator.validate(
                new HealthConnectRecord("x1", type, iso("2026-03-10T05:00:00Z"),
                        iso("2026-03-10T06:00:00Z"), new BigDecimal("10"), "count"),
                ZONE, TODAY, EARLIEST);

        assertThat(result)
                .as("%s is out of Phase 11 scope and must not be accepted", type)
                .isNull();
    }

    @Test
    @DisplayName("a zero-step interval is accepted, because zero is a real reading")
    void acceptsZero() {
        var result = HealthConnectIngestValidator.validate(
                steps("z1", "2026-03-10T02:30:00Z", "2026-03-10T03:30:00Z", "0", "count"),
                ZONE, TODAY, EARLIEST);

        assertThat(result).isNotNull();
        assertThat(result.value()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a structurally impossible value is refused without any medical judgement")
    void refusesStructurallyImpossibleValues() {
        // Negative steps, zero weight, and a body fat above 100% are corrupt as data, not merely
        // unusual for a particular person.
        assertThat(HealthConnectIngestValidator.validate(
                steps("a", "2026-03-10T02:30:00Z", "2026-03-10T03:30:00Z", "-5", "count"),
                ZONE, TODAY, EARLIEST)).isNull();
        assertThat(HealthConnectIngestValidator.validate(
                new HealthConnectRecord("b", "weight", iso("2026-03-10T05:00:00Z"),
                        iso("2026-03-10T05:00:00Z"), BigDecimal.ZERO, "kg"),
                ZONE, TODAY, EARLIEST)).isNull();
        assertThat(HealthConnectIngestValidator.validate(
                new HealthConnectRecord("c", "body_fat", iso("2026-03-10T05:00:00Z"),
                        iso("2026-03-10T05:00:00Z"), new BigDecimal("140"), "percent"),
                ZONE, TODAY, EARLIEST)).isNull();
    }

    @Test
    @DisplayName("a record beyond the D7 window is refused, so history retention stays bounded")
    void refusesRecordsOutsideTheWindow() {
        // 30 days back is inside the window; 40 days back is not.
        assertThat(HealthConnectIngestValidator.validate(
                steps("i", "2026-02-09T02:00:00Z", "2026-02-09T03:00:00Z", "10", "count"),
                ZONE, TODAY, EARLIEST)).isNotNull();
        assertThat(HealthConnectIngestValidator.validate(
                steps("o", "2026-01-30T02:00:00Z", "2026-01-30T03:00:00Z", "10", "count"),
                ZONE, TODAY, EARLIEST)).isNull();
    }

    @Test
    @DisplayName("a strictly future timestamp is refused")
    void refusesFutureRecords() {
        assertThat(HealthConnectIngestValidator.validate(
                steps("f", "2030-01-01T00:00:00Z", "2030-01-01T01:00:00Z", "10", "count"),
                ZONE, TODAY, EARLIEST)).isNull();
    }

    @Test
    @DisplayName("rejection reasons are stable, specific, and carry no health value")
    void reasonsAreStableAndSafe() {
        assertThat(HealthConnectIngestValidator.reason(null))
                .isEqualTo(HealthConnectIngestValidator.MISSING_RECORD_ID);
        assertThat(HealthConnectIngestValidator.reason(steps("", "2026-03-10T02:00:00Z",
                "2026-03-10T03:00:00Z", "10", "count")))
                .isEqualTo(HealthConnectIngestValidator.MISSING_RECORD_ID);
        assertThat(HealthConnectIngestValidator.reason(steps("x", "2026-03-10T02:00:00Z",
                "2026-03-10T03:00:00Z", "10", "lb")))
                .isEqualTo(HealthConnectIngestValidator.UNIT_MISMATCH);
        assertThat(HealthConnectIngestValidator.reason(steps("x", "2026-03-10T03:00:00Z",
                "2026-03-10T02:00:00Z", "10", "count")))
                .isEqualTo(HealthConnectIngestValidator.INVALID_INTERVAL);
        assertThat(HealthConnectIngestValidator.reason(new HealthConnectRecord("x", "steps",
                iso("2026-03-10T02:00:00Z"), iso("2026-03-10T03:00:00Z"), null, "count")))
                .isEqualTo(HealthConnectIngestValidator.MISSING_VALUE);

        // A reason is a category, never a payload: it must not echo the submitted value.
        assertThat(HealthConnectIngestValidator.reason(steps("x", "2026-03-10T02:00:00Z",
                "2026-03-10T03:00:00Z", "99999", "count")))
                .doesNotContain("99999");
    }

    @Test
    @DisplayName("a record id too long for the column is refused rather than truncated")
    void refusesOverlongRecordIds() {
        String tooLong = "r".repeat(HealthConnectIngestValidator.MAX_RECORD_ID_LENGTH + 1);
        assertThat(HealthConnectIngestValidator.validate(
                steps(tooLong, "2026-03-10T02:00:00Z", "2026-03-10T03:00:00Z", "10", "count"),
                ZONE, TODAY, EARLIEST)).isNull();
        assertThat(HealthConnectIngestValidator.reason(
                steps(tooLong, "2026-03-10T02:00:00Z", "2026-03-10T03:00:00Z", "10", "count")))
                .isEqualTo(HealthConnectIngestValidator.RECORD_ID_TOO_LONG);
    }

    @Test
    @DisplayName("a record exactly at the id length limit is still accepted")
    void acceptsRecordIdAtTheLimit() {
        String atLimit = "r".repeat(HealthConnectIngestValidator.MAX_RECORD_ID_LENGTH);
        assertThat(HealthConnectIngestValidator.validate(
                steps(atLimit, "2026-03-10T02:00:00Z", "2026-03-10T03:00:00Z", "10", "count"),
                ZONE, TODAY, EARLIEST)).isNotNull();
    }
}
