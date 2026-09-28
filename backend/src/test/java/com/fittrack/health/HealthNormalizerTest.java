package com.fittrack.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for canonical unit conversion.
 *
 * <p>Conversion is the quiet failure mode of a health integration: a kilogram value written into a
 * column named weight_lb is stored happily and surfaces much later as a wrong number. These tests
 * pin the arithmetic so a refactor cannot quietly change it.
 */
class HealthNormalizerTest {

    @Test
    @DisplayName("70 kg converts to pounds within rounding tolerance")
    void kilogramsToPounds() {
        BigDecimal pounds = HealthNormalizer.kilogramsToPounds(new BigDecimal("70"));
        assertThat(pounds.doubleValue()).isCloseTo(154.32d, org.assertj.core.data.Offset.offset(0.01d));
    }

    @Test
    @DisplayName("1 kg converts to 2.20 lb after the two-decimal rounding the column assumes")
    void kilogramFactor() {
        // The stored value is rounded to two decimals, so 2.2046 lb becomes 2.20. Asserting the
        // unrounded constant here would be asserting a precision the column does not keep.
        assertThat(HealthNormalizer.kilogramsToPounds(new BigDecimal("1")).doubleValue())
                .isCloseTo(2.20d, org.assertj.core.data.Offset.offset(0.005d));
    }

    @Test
    @DisplayName("weight conversion is rounded to two decimals, the precision the column assumes")
    void weightRounding() {
        assertThat(HealthNormalizer.kilogramsToPounds(new BigDecimal("70")).scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("a null weight stays null, which is not measured rather than zero")
    void nullWeightStaysNull() {
        assertThat(HealthNormalizer.kilogramsToPounds(null)).isNull();
        assertThat(HealthNormalizer.pounds(null)).isNull();
    }

    @Test
    @DisplayName("pounds pass through unchanged apart from scale")
    void poundsPassThrough() {
        assertThat(HealthNormalizer.pounds(new BigDecimal("180.5")).doubleValue()).isEqualTo(180.5d);
    }

    @Test
    @DisplayName("seconds convert to whole minutes, rounded to nearest")
    void secondsToMinutes() {
        assertThat(HealthNormalizer.secondsToMinutes(1800)).isEqualTo(30);
        assertThat(HealthNormalizer.secondsToMinutes(1799)).isEqualTo(30);
        assertThat(HealthNormalizer.secondsToMinutes(90)).isEqualTo(2);
        assertThat(HealthNormalizer.secondsToMinutes(0)).isZero();
    }

    @Test
    @DisplayName("kilometres convert to miles within tolerance")
    void kilometresToMiles() {
        assertThat(HealthNormalizer.kilometresToMiles(new BigDecimal("1")).doubleValue())
                .isCloseTo(0.6214d, org.assertj.core.data.Offset.offset(0.0001d));
        assertThat(HealthNormalizer.kilometresToMiles(new BigDecimal("10")).doubleValue())
                .isCloseTo(6.2137d, org.assertj.core.data.Offset.offset(0.0001d));
    }

    @Test
    @DisplayName("minutes pass through unchanged")
    void minutesPassThrough() {
        assertThat(HealthNormalizer.minutes(45)).isEqualTo(45);
    }

    @Test
    @DisplayName("a well-formed ISO date parses")
    void parsesIsoDate() {
        assertThat(HealthNormalizer.parseDate("2026-09-10")).isEqualTo(LocalDate.of(2026, 9, 10));
        assertThat(HealthNormalizer.parseDate("  2026-09-10  ")).isEqualTo(LocalDate.of(2026, 9, 10));
    }

    @Test
    @DisplayName("a malformed or absent date yields null rather than throwing")
    void rejectsMalformedDate() {
        assertThat(HealthNormalizer.parseDate("10/09/2026")).isNull();
        assertThat(HealthNormalizer.parseDate("")).isNull();
        assertThat(HealthNormalizer.parseDate(null)).isNull();
        assertThat(HealthNormalizer.parseDate("2026-13-45")).isNull();
    }
}
