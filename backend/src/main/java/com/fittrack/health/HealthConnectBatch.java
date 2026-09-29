package com.fittrack.health;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The inbound Health Connect contract: exactly what a future Android bridge POSTs.
 *
 * <h2>Why interval records and not daily totals</h2>
 * The Phase 1-15 pull contract ({@code HealthProvider.ActivityRecord}) carries one already-aggregated
 * row per date, which is correct for a server that can ask a cloud provider for a daily total. It is
 * the <b>wrong</b> shape for Health Connect, which hands out interval records. Accepting a
 * pre-aggregated daily number would mean trusting the client's arithmetic, would make a corrected or
 * deleted source record impossible to reconcile, and would make midnight-crossing records
 * unresolvable. So this contract transports what Health Connect actually supplies and the server
 * aggregates.
 *
 * <h2>Fields, and where each one comes from</h2>
 * <ul>
 *   <li>{@code record_id} - the Health Connect record id. Stable for the life of the record, and
 *       reused when the record is corrected. This is the idempotency key.</li>
 *   <li>{@code record_type} - one of the four Phase 11 types. Anything else is rejected by name.</li>
 *   <li>{@code start_time} / {@code end_time} - ISO-8601 instants, matching Health Connect's
 *       {@code startTime}/{@code endTime}. For the two instantaneous types {@code end_time} must equal
 *       {@code start_time}, mirroring how those records are a single point in time.</li>
 *   <li>{@code value} - {@code count} for steps, {@code energy} in kilocalories for active calories,
 *       {@code weight} in <b>kilograms</b> for weight, and {@code bodyFatPercentage} in percent.</li>
 *   <li>{@code unit} - must equal the type's Health Connect native unit. It is required rather than
 *       assumed so a bridge that converts early is rejected instead of being double-converted.</li>
 *   <li>{@code timezone} - the device's IANA zone at the time of reading. Required, never defaulted.</li>
 *   <li>{@code deleted_record_ids} - record ids Health Connect reports as deleted. Health Connect
 *       reports a deletion as an id only, so the server must already hold the record to reconcile it.</li>
 *   <li>{@code changes_token} - the Android client's own opaque Health Connect resume handle. Stored
 *       separately from the server's {@code sync_cursor}; the two are never interchanged.</li>
 *   <li>{@code permission_status} - the client's report of its own Health Connect permissions. UX
 *       metadata only. It never authorizes anything.</li>
 * </ul>
 *
 * <h2>What is deliberately absent</h2>
 * No {@code user_id}: the caller is the authenticated user and the server derives ownership from the
 * token, so a body field claiming another identity would be a spoofing vector. No {@code provider}:
 * the device's registered provider is authoritative and is never taken from the request. No
 * {@code access_token}, {@code client_secret} or any credential - D6 keeps server-held credentials
 * deferred, because Health Connect needs none.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record HealthConnectBatch(
        /** The records to upsert. At most {@link HealthConnectIngestService#MAX_RECORDS} in total. */
        List<HealthConnectRecord> records,
        /** Platform record ids the source reports as deleted. */
        List<String> deletedRecordIds,
        /** The client's opaque Health Connect changes token. Never the server sync_cursor. */
        String changesToken,
        /** The client's own view of its Health Connect permission state. UX metadata only. */
        String permissionStatus,
        /** The IANA zone the readings were taken in. Required; there is no default. */
        String timezone) {

    /** Convenience for the common case of a batch that carries only upserts. */
    public List<HealthConnectRecord> recordsOrEmpty() {
        return records == null ? List.of() : records;
    }

    /** Convenience for the common case of a batch that deletes nothing. */
    public List<String> deletionsOrEmpty() {
        return deletedRecordIds == null ? List.of() : deletedRecordIds;
    }

    /**
     * One source record exactly as Health Connect supplied it.
     *
     * <p>Kept as a record with nullable boxed types rather than primitives so "absent" is
     * distinguishable from "zero": a step record with no value is malformed and must be rejected,
     * whereas a genuine reading of zero steps is valid and must be stored.
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record HealthConnectRecord(
            String recordId,
            String recordType,
            Instant startTime,
            Instant endTime,
            BigDecimal value,
            String unit) {
    }
}
