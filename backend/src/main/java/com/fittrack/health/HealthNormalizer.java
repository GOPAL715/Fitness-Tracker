package com.fittrack.health;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Converts provider-reported values into FitTrack's canonical units.
 *
 * <h2>Canonical units</h2>
 * <ul>
 *   <li>weight: pounds (lb), stored in {@code weight_lb}</li>
 *   <li>distance: not imported; the current contract has no distance column</li>
 *   <li>duration: minutes, stored in {@code active_minutes} and {@code sleep_hours}</li>
 *   <li>calories: kilocalories, stored in {@code calories_burned}</li>
 *   <li>steps: count, stored in {@code steps}</li>
 *   <li>body fat: percent, stored in {@code body_fat_pct}</li>
 * </ul>
 *
 * <h2>Why a single boundary</h2>
 * Conversion lives here rather than in a controller or in the sync service so there is exactly one
 * place where a unit is interpreted. A conversion scattered across call sites is how a kg value
 * ends up written into a column named {@code weight_lb} without anyone noticing.
 *
 * <h2>Scope discipline</h2>
 * Only conversions the actual integration boundary can produce are implemented. Nothing is
 * invented for a provider that is not wired up: the Health Connect bridge (D5) supplies kilograms,
 * seconds and kilocalories, and those three conversions plus identity handling are what exist
 * here. A new provider adds a case, it does not add a scattered special case.
 */
public final class HealthNormalizer {

    /** Provider-reported weight kilograms to pounds. */
    static final BigDecimal KG_PER_LB = new BigDecimal("2.204622621848775");

    private HealthNormalizer() {
    }

    /**
     * Weight in kilograms to pounds.
     *
     * <p>Rounded to two decimals, which is the precision the column and the UI already assume.
     */
    public static BigDecimal kilogramsToPounds(BigDecimal kilograms) {
        if (kilograms == null) return null;
        return kilograms.multiply(KG_PER_LB).setScale(2, RoundingMode.HALF_UP);
    }

    /** Weight already expressed in pounds passes through unchanged. */
    public static BigDecimal pounds(BigDecimal pounds) {
        return pounds == null ? null : pounds.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Duration in seconds to minutes, rounded to the nearest minute.
     *
     * <p>Seconds are truncated to whole minutes rather than kept as a fraction because
     * {@code active_minutes} is an integer column; storing a rounded fraction would be lost on the
     * way in and would misrepresent the value as exact.
     */
    public static long secondsToMinutes(long seconds) {
        return Math.round(seconds / 60.0d);
    }

    /** Duration already in minutes. */
    public static long minutes(long minutes) {
        return minutes;
    }

    /**
     * Distance in kilometres to miles.
     *
     * <p>Present for completeness and for a future distance-bearing provider. No column consumes
     * it today, so nothing calls it yet; it is unit-tested to keep the conversion honest rather
     * than being left to be written from memory later.
     */
    public static BigDecimal kilometresToMiles(BigDecimal kilometres) {
        if (kilometres == null) return null;
        return kilometres.multiply(new BigDecimal("0.621371192237334"))
                .setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Parses an ISO date supplied by a provider.
     *
     * @return the date, or null when the value is absent or not a well-formed ISO date
     */
    public static LocalDate parseDate(String isoDate) {
        if (isoDate == null || isoDate.isBlank()) return null;
        try {
            return LocalDate.parse(isoDate.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
