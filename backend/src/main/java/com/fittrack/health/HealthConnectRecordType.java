package com.fittrack.health;

import java.util.Locale;
import java.util.Set;

/**
 * The Phase 11 record types, and the verified Health Connect semantics behind each one (D2).
 *
 * <h2>Scope</h2>
 * Exactly four types: steps, active calories, weight, body fat. Sleep, exercise sessions, distance,
 * total calories, heart rate, HRV, readiness and stress are deliberately absent, and
 * {@link #isSupported} rejects them by name so a client that sends one gets a clear
 * {@code unsupported_record_type} rather than a silently dropped field.
 *
 * <h2>Verified platform semantics</h2>
 * Taken from Google's Health Connect record documentation, not from memory:
 * <ul>
 *   <li><b>Steps</b> - {@code StepsRecord} carries {@code count}, {@code startTime}, {@code endTime},
 *       {@code startZoneOffset}, {@code endZoneOffset}, {@code metadata}. Its supported aggregation is
 *       {@code COUNT_TOTAL} and the documented way to read a day is a total over a time range, so
 *       per-record counts across a day are <b>additive</b>.</li>
 *   <li><b>Active calories</b> - {@code ActiveCaloriesBurnedRecord} has the same interval shape with
 *       {@code energy} in kilocalories, and aggregates to {@code ENERGY_TOTAL}, so it is additive
 *       for the same reason.</li>
 *   <li><b>Weight / body fat</b> - {@code WeightRecord} and {@code BodyFatPercentageRecord} are
 *       instantaneous single measurements, not intervals. Their supported aggregations are
 *       {@code WEIGHT_AVG}/{@code WEIGHT_MAX}/{@code WEIGHT_MIN}, never a total, so summing them
 *       would be meaningless and they are treated as a latest-per-day selection instead.</li>
 * </ul>
 *
 * <h2>Units</h2>
 * The {@link #unit} is the unit the <b>bridge sends</b>, which is the Health Connect native unit.
 * The server normalises exactly once, in {@link HealthConnectAggregator}, so a kilogram value can
 * never reach a column named {@code weight_lb} without a conversion.
 */
public enum HealthConnectRecordType {

    /** Additive interval. {@code StepsRecord.count}, Health Connect unit: count. */
    STEPS("steps", "count", true, "count"),

    /**
     * Additive interval. {@code ActiveCaloriesBurnedRecord.energy}, Health Connect unit: kilocalories.
     *
     * <p>Active calories only. {@code TotalCaloriesBurnedRecord} is a different record type and is out
     * of scope under D2; writing it into {@code calories_burned} would silently change the meaning of
     * a number the rest of FitTrack already treats as active burn.
     */
    ACTIVE_CALORIES("active_calories", "kcal", true, "calories"),

    /** Instantaneous. {@code WeightRecord.weight}, Health Connect unit: kilograms. */
    WEIGHT("weight", "kg", false, "weight"),

    /** Instantaneous. {@code BodyFatPercentageRecord.bodyFatPercentage}, Health Connect unit: percent. */
    BODY_FAT("body_fat", "percent", false, "body_fat");

    private final String wireName;
    private final String unit;
    private final boolean interval;
    private final String canonicalField;

    HealthConnectRecordType(String wireName, String unit, boolean interval, String canonicalField) {
        this.wireName = wireName;
        this.unit = unit;
        this.interval = interval;
        this.canonicalField = canonicalField;
    }

    /** The value accepted in the transport {@code record_type} field. */
    public String wireName() {
        return wireName;
    }

    /** The Health Connect native unit this type is transported in. */
    public String unit() {
        return unit;
    }

    /**
     * True when the record spans an interval whose value contributes additively to a day.
     *
     * <p>False for instantaneous measurements, which are selected rather than summed.
     */
    public boolean isInterval() {
        return interval;
    }

    /** The FitTrack column family this type feeds, used in logs and documentation only. */
    public String canonicalField() {
        return canonicalField;
    }

    /**
     * Resolves a wire value to a supported type.
     *
     * @return the type, or null when it is absent or outside the Phase 11 scope
     */
    public static HealthConnectRecordType fromWire(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (HealthConnectRecordType type : values()) {
            if (type.wireName.equals(normalized)) {
                return type;
            }
        }
        return null;
    }

    /** The supported wire values, for the error message and the contract documentation. */
    public static Set<String> wireNames() {
        return Set.of(STEPS.wireName, ACTIVE_CALORIES.wireName, WEIGHT.wireName, BODY_FAT.wireName);
    }
}