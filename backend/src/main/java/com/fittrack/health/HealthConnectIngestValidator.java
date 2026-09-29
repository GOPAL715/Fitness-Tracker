package com.fittrack.health;

import com.fittrack.health.HealthConnectBatch.HealthConnectRecord;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Technical validation of inbound Health Connect records (D8).
 *
 * <p>Same discipline as {@link HealthRecordValidator} at the new boundary: rejects records that are
 * <b>structurally</b> impossible or unusable, and never judges medical plausibility. FitTrack has no
 * clinical reference ranges, and inventing thresholds would silently discard a user's real data on the
 * basis of a number nobody audited.
 *
 * <h2>Partial rejection</h2>
 * One bad record must not fail the whole batch. Each is validated independently and a failure yields a
 * stable reason, so a bridge sees what was dropped while the rest still lands. The batch is rejected
 * outright only when the request itself is unusable: no resolvable timezone, or over the record cap.
 */
public final class HealthConnectIngestValidator {

    // Stable reason codes describing what is technically wrong. They make no health claim.
    public static final String MISSING_RECORD_ID = "missing_record_id";
    public static final String RECORD_ID_TOO_LONG = "record_id_too_long";
    public static final String UNSUPPORTED_RECORD_TYPE = "unsupported_record_type";
    public static final String MISSING_VALUE = "missing_value";
    public static final String NEGATIVE_VALUE = "negative_value";
    public static final String INVALID_INTERVAL = "invalid_interval";
    public static final String INSTANT_NOT_POINT = "instant_not_point";
    public static final String UNIT_MISMATCH = "unit_mismatch";
    public static final String INVALID_WEIGHT = "invalid_weight";
    public static final String EXCESSIVE_VALUE = "excessive_value";

    /** Bounds what a counter column can physically hold, not what is healthy. */
    private static final BigDecimal MAX_STEPS = new BigDecimal("200000");
    private static final BigDecimal MAX_CALORIES = new BigDecimal("100000");

    /** Matches the Phase 1-10 length limit on provider_record_id. */
    static final int MAX_RECORD_ID_LENGTH = 120;

    private HealthConnectIngestValidator() {
    }

    /**
     * One accepted record, normalised into the values the ledger stores.
     *
     * <p>Carries the value in its <b>native</b> unit. Conversion happens once, later, during recompute,
     * so kilograms are never written straight into a pounds column.
     */
    public record Normalized(HealthConnectRecordType type, String recordId, Instant start, Instant end,
                             BigDecimal value, LocalDate startDay) {
    }

    /**
     * Validates one record, or returns null when it is rejected.
     *
     * @param today          current day <b>in the user's own zone</b>, so a record is judged against
     *                       the calendar the user lives in, not the server's
     * @param earliestAllowed oldest local day accepted here, or null for no lower bound
     */
    public static Normalized validate(HealthConnectRecord record, ZoneId zone, LocalDate today,
                                      LocalDate earliestAllowed) {
        if (record == null || record.recordId() == null || record.recordId().isBlank()
                || record.recordId().length() > MAX_RECORD_ID_LENGTH) {
            return null;
        }
        HealthConnectRecordType type = HealthConnectRecordType.fromWire(record.recordType());
        if (type == null || record.value() == null || record.value().signum() < 0) {
            return null;
        }
        // Requiring the native unit means a bridge that converts early is rejected rather than being
        // silently double-converted into a pounds column.
        if (!type.unit().equalsIgnoreCase(record.unit() == null ? "" : record.unit().trim())) {
            return null;
        }
        Instant start = record.startTime();
        if (start == null) {
            return null;
        }
        if (type.isInterval()) {
            // A zero or negative span carries no elapsed time, so it cannot be attributed to a day.
            if (record.endTime() == null || !record.endTime().isAfter(start)) {
                return null;
            }
        } else if (record.endTime() == null || !record.endTime().equals(start)) {
            // Instantaneous records are one point in time. Accepting a non-zero span would let a client
            // smuggle an interval into a type the platform never aggregates as a total.
            return null;
        }
        if (start.isAfter(Instant.now().plusSeconds(86_400L))) {
            return null;
        }
        LocalDate startDay = HealthConnectAggregator.localDayOf(start, zone);
        if (startDay == null || startDay.isAfter(today)
                || (earliestAllowed != null && startDay.isBefore(earliestAllowed))) {
            return null;
        }
        if (type == HealthConnectRecordType.WEIGHT && record.value().signum() <= 0) {
            return null;
        }
        if (type == HealthConnectRecordType.STEPS && record.value().compareTo(MAX_STEPS) > 0) {
            return null;
        }
        if (type == HealthConnectRecordType.ACTIVE_CALORIES && record.value().compareTo(MAX_CALORIES) > 0) {
            return null;
        }
        // A percentage bound, not a body-composition opinion about any individual.
        if (type == HealthConnectRecordType.BODY_FAT && record.value().compareTo(new BigDecimal("100")) > 0) {
            return null;
        }
        return new Normalized(type, record.recordId(), start, record.endTime(), record.value(), startDay);
    }

    /** The reason a record was rejected, for the structured response. Carries no health value. */
    public static String reason(HealthConnectRecord record) {
        if (record == null || record.recordId() == null || record.recordId().isBlank()) {
            return MISSING_RECORD_ID;
        }
        if (record.recordId().length() > MAX_RECORD_ID_LENGTH) {
            return RECORD_ID_TOO_LONG;
        }
        HealthConnectRecordType type = HealthConnectRecordType.fromWire(record.recordType());
        if (type == null) {
            return UNSUPPORTED_RECORD_TYPE;
        }
        if (record.value() == null) {
            return MISSING_VALUE;
        }
        if (record.value().signum() < 0) {
            return NEGATIVE_VALUE;
        }
        if (!type.unit().equalsIgnoreCase(record.unit() == null ? "" : record.unit().trim())) {
            return UNIT_MISMATCH;
        }
        if (record.startTime() == null) {
            return INVALID_INTERVAL;
        }
        if (type.isInterval()) {
            if (record.endTime() == null || !record.endTime().isAfter(record.startTime())) {
                return INVALID_INTERVAL;
            }
        } else if (record.endTime() == null || !record.endTime().equals(record.startTime())) {
            return INSTANT_NOT_POINT;
        }
        if (type == HealthConnectRecordType.WEIGHT && record.value().signum() <= 0) {
            return INVALID_WEIGHT;
        }
        return EXCESSIVE_VALUE;
    }
}
