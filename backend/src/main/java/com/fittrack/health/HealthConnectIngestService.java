package com.fittrack.health;

import com.fittrack.health.HealthConnectBatch.HealthConnectRecord;
import com.fittrack.health.HealthConnectIngestValidator.Normalized;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/**
 * The inbound Health Connect ingestion path: validates, normalises once, retains the source record,
 * then derives the Phase 10 daily aggregates (D2, D3, D5, D8, D10).
 *
 * <h2>Why source records are retained</h2>
 * Health Connect reports a deletion as a <b>record id only</b>; the deleted entry carries neither the
 * value nor the timestamps. A deletion therefore cannot be reconciled from the request alone - the
 * server must already hold the record to know which day it touched and what it contributed. Every
 * accepted record is written to {@code health_connect_records} first, and daily aggregates are
 * <b>always recomputed from that ledger</b>, never incremented. Increments would drift the moment a
 * record was corrected and would be unrecoverable after a deletion.
 *
 * <h2>What is derived, and where it lands</h2>
 * Steps and active calories are additive, so a day is the sum of every source record of that type whose
 * interval overlaps the day. Weight and body fat are instantaneous, so a day takes the latest reading.
 * The result is written as <b>one provider row per (device, day)</b> keyed by a synthetic id, which is
 * what the Phase 10 canonical views expect: one competing measurement per source per day, selected not
 * summed. Manual rows are never touched and never displaced.
 *
 * <p>A batch is one transaction: a failure part-way must not leave a ledger row without its aggregate.
 */
@Service
public class HealthConnectIngestService {

    private static final Logger log = LoggerFactory.getLogger(HealthConnectIngestService.class);

    /** D8: FitTrack's own safety ceiling, not a Google quota claim. */
    public static final int MAX_RECORDS = 500;

    /** The one provider this push path serves. Never taken from the request body. */
    public static final String PROVIDER = "health-connect";

    private static final String AGGREGATE_PREFIX = "hc-agg:";
    private static final String BODY_AGGREGATE_PREFIX = "hc-body:";

    private final JdbcTemplate jdbc;

    public HealthConnectIngestService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Outcome of one ingestion: counts and reason categories only.
     *
     * <p>No health values and no record ids, so a response can be logged or shown without carrying
     * sensitive data.
     */
    public record IngestResult(int recordsReceived, int recordsAccepted, int recordsRejected,
                               int recordsDeleted, int daysRecomputed, String timezone,
                               Map<String, Integer> rejectedReasons) {
    }

    /**
     * Ingests one bounded batch for a device owned by the caller.
     *
     * @param zone             the user's IANA zone, already validated by the caller
     * @param earliestAccepted oldest local day accepted, or null for no lower bound
     */
    @Transactional
    public IngestResult ingest(UUID deviceId, UUID userId, HealthConnectBatch batch, ZoneId zone,
                               String canonicalTimezone, LocalDate earliestAccepted) {
        Map<String, Object> device = owned(deviceId, userId);
        String provider = String.valueOf(device.get("provider"));
        if (!PROVIDER.equals(provider)) {
            // The registered provider is authoritative. A client cannot push Health Connect data into
            // a connection registered as something else.
            throw new NoSuchElementException("device does not accept Health Connect records");
        }
        List<HealthConnectRecord> incoming = batch.recordsOrEmpty();
        // deletedRecordIds may be absent, meaning "this batch deletes nothing". Reading the raw
        // accessor would null-dereference on every batch that only carries upserts, which is the
        // common case, so the null-safe accessor is used and absent is genuinely treated as empty.
        List<String> deletions = batch.deletionsOrEmpty();
        int received = incoming.size() + deletions.size();
        if (received > MAX_RECORDS) {
            throw new IllegalArgumentException("batch exceeds " + MAX_RECORDS + " records");
        }
        LocalDate today = HealthConnectAggregator.localDayOf(Instant.now(), zone);
        List<Normalized> accepted = new ArrayList<>();
        Map<String, Integer> reasons = new LinkedHashMap<>();
        for (HealthConnectRecord record : incoming) {
            Normalized normalized =
                    HealthConnectIngestValidator.validate(record, zone, today, earliestAccepted);
            if (normalized == null) {
                reasons.merge(HealthConnectIngestValidator.reason(record), 1, Integer::sum);
            } else {
                accepted.add(normalized);
            }
        }
        int rejected = incoming.size() - accepted.size();
        // The ledger write is what makes corrections and deletions reconcilable, so it happens first
        // and every later step reads back from it. dirtyDays is declared before the write because
        // persistLedger contributes to it: a corrected record must also clear the day it used to sit on.
        Set<LocalDate> dirtyDays = new LinkedHashSet<>();
        persistLedger(userId, deviceId, accepted, dirtyDays, zone);
        persistTimezone(userId, canonicalTimezone);
        for (Normalized record : accepted) {
            markDirty(dirtyDays, record, zone);
        }
        // The batch already resolved and validated a zone, so the caller's calendar is used rather than
        // re-reading (and possibly defaulting) the stored one.
        int deleted = applyDeletions(userId, deviceId, deletions, dirtyDays, zone);
        // A correction can move a record between days, so the day it used to occupy is recomputed too.
        int recomputed = recompute(userId, deviceId, dirtyDays, zone);
        persistClientState(deviceId, userId, batch);
        markStatus(deviceId, userId, "synced", null);
        log.info("health_ingest_completed provider={} device_id={} received={} accepted={} rejected={}"
                        + " deleted={} days_recomputed={}",
                provider, deviceId, received, accepted.size(), rejected, deleted, recomputed);
        return new IngestResult(received, accepted.size(), rejected, deleted, recomputed,
                canonicalTimezone, Map.copyOf(reasons));
    }

    /**
     * Writes the source ledger, one statement for the whole batch.
     *
     * <p>Keyed on {@code (user_id, device_id, record_id)}, so a corrected record updates in place
     * instead of accumulating, and a replayed batch is a no-op. {@code updated_at} moves but the
     * aggregate is recomputed from the values, so a replay cannot change any number.
     */
    private void persistLedger(UUID userId, UUID deviceId, List<Normalized> records,
                               Set<LocalDate> dirtyDays, ZoneId zone) {
        if (records.isEmpty()) {
            return;
        }
        // The days each record occupied BEFORE this write are captured first. The upsert below replaces
        // start_time/end_time in place, so once it has run the previous day is unrecoverable - and a
        // correction that moves a record to another calendar day would leave the day it left holding a
        // stale total that nothing contributes to. Reading them up front is what makes both the old
        // and the new day get recomputed.
        List<String> ids = new ArrayList<>(records.size());
        for (Normalized record : records) {
            ids.add(record.recordId());
        }
        for (LocalDate previous : previousDays(userId, deviceId, ids, zone)) {
            dirtyDays.add(previous);
        }
        List<Object[]> batch = new ArrayList<>(records.size());
        for (Normalized record : records) {
            batch.add(new Object[]{UUID.randomUUID(), userId, deviceId, record.recordId(),
                    record.type().wireName(), java.sql.Date.valueOf(record.startDay()),
                    java.sql.Timestamp.from(record.start()), java.sql.Timestamp.from(record.end()),
                    record.value(), record.type().unit()});
        }
        jdbc.batchUpdate("insert into health_connect_records"
                        + "(id,user_id,device_id,record_id,record_type,local_date,start_time,end_time,value,unit)"
                        + " values (?,?,?::uuid,?,?,?::date,?,?,?,?)"
                        + " on conflict (user_id,device_id,record_id) do update set"
                        + " record_type=excluded.record_type,local_date=excluded.local_date,"
                        + " start_time=excluded.start_time,end_time=excluded.end_time,value=excluded.value,"
                        + " unit=excluded.unit,updated_at=now()",
                batch);
    }

    /**
     * The local days the given records occupied before the current write.
     *
     * <p>Interval records are re-split under the caller's zone so a record that has moved across
     * midnight contributes to every day it now touches, not just the one stored in {@code local_date}.
     * Records that are new (no prior row) contribute nothing, which is correct: there is no previous
     * day to clear.
     */
    private Set<LocalDate> previousDays(UUID userId, UUID deviceId, List<String> recordIds, ZoneId zone) {
        Set<LocalDate> days = new LinkedHashSet<>();
        if (recordIds.isEmpty()) {
            return days;
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select record_id, record_type, local_date, start_time, end_time, value"
                        + " from health_connect_records where user_id=?::uuid and device_id=?::uuid"
                        + " and record_id in (" + placeholders(recordIds.size()) + ")",
                prependArgs(userId, deviceId, recordIds));
        for (Map<String, Object> row : rows) {
            HealthConnectRecordType type =
                    HealthConnectRecordType.fromWire(String.valueOf(row.get("record_type")));
            if (type != null && type.isInterval()) {
                Instant start = ((java.sql.Timestamp) row.get("start_time")).toInstant();
                Instant end = ((java.sql.Timestamp) row.get("end_time")).toInstant();
                java.math.BigDecimal value = (java.math.BigDecimal) row.get("value");
                days.addAll(HealthConnectAggregator
                        .splitIntervalByLocalDay(start, end, value, zone).keySet());
            } else {
                days.add(((java.sql.Date) row.get("local_date")).toLocalDate());
            }
        }
        return days;
    }



    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static Object[] prependArgs(UUID userId, UUID deviceId, List<String> recordIds) {
        Object[] args = new Object[recordIds.size() + 2];
        args[0] = userId;
        args[1] = deviceId;
        for (int i = 0; i < recordIds.size(); i++) {
            args[i + 2] = recordIds.get(i);
        }
        return args;
    }

    /**
     * Persists the user's IANA zone (D3).
     *
     * <p>Written only when the user has none, or when it differs, so a zone change is honoured rather
     * than pinned by the first batch that happened to arrive. Existing users are never backfilled with
     * a guess: the column stays NULL until the client states a real zone.
     */
    private void persistTimezone(UUID userId, String canonicalTimezone) {
        if (canonicalTimezone == null) {
            return;
        }
        jdbc.update("update app_users set timezone=? where id=?::uuid"
                        + " and (timezone is null or timezone<>?)",
                canonicalTimezone, userId, canonicalTimezone);
    }

    /**
     * Marks every local day a record touches, so each one is recomputed.
     *
     * <p>For an interval this is every day the interval overlaps, not just the start day: a record
     * crossing midnight contributes to two days, and recomputing only the start day would leave the
     * second day stale.
     */
    private void markDirty(Set<LocalDate> dirty, Normalized record, ZoneId zone) {
        if (record.type().isInterval()) {
            dirty.addAll(HealthConnectAggregator
                    .splitIntervalByLocalDay(record.start(), record.end(), record.value(), zone).keySet());
        } else {
            dirty.add(record.startDay());
        }
    }

    /**
     * Removes ledger rows the source reported as deleted, and marks the days they touched dirty (D5).
     *
     * <p>The affected day is read from the ledger row <b>before</b> the delete, because the request
     * carries only a record id. Recomputation then subtracts the record's real contribution by
     * rebuilding the day from whatever remains, rather than by subtracting a remembered number.
     *
     * @return how many rows were actually removed
     */
    private int applyDeletions(UUID userId, UUID deviceId, List<String> deletions, Set<LocalDate> dirty) {
        return applyDeletions(userId, deviceId, deletions, dirty, storedZone(userId));
    }

    /**
     * Deletion reconciliation using an explicit zone.
     *
     * <p>The zone is the caller's validated one, so the days a deleted record touched are derived
     * under exactly the same calendar the ingest was dated with. If no zone can be established the
     * stored {@code local_date} is used for the day, which is the honest best available answer for a
     * single-day record rather than an invented boundary.
     */
    private int applyDeletions(UUID userId, UUID deviceId, List<String> deletions, Set<LocalDate> dirty,
                              ZoneId zone) {
        int removed = 0;
        for (String recordId : deletions) {
            if (recordId == null || recordId.isBlank() || recordId.length() > HealthConnectIngestValidator.MAX_RECORD_ID_LENGTH) {
                continue;
            }
            List<Map<String, Object>> existing = jdbc.queryForList(
                    "select record_type,local_date,start_time,end_time,value from health_connect_records"
                            + " where user_id=?::uuid and device_id=?::uuid and record_id=?",
                    userId, deviceId, recordId);
            if (existing.isEmpty()) {
                // Already gone, or never seen. Deleting something absent is not an error, but it is
                // also not silently treated as a successful reconciliation of data we never held.
                continue;
            }
            Map<String, Object> row = existing.get(0);
            HealthConnectRecordType type = HealthConnectRecordType.fromWire(String.valueOf(row.get("record_type")));
            if (type != null && type.isInterval() && zone != null) {
                // An interval can have covered more than the single day recorded in local_date, so the
                // split is re-derived under the caller's calendar.
                Instant start = ((java.sql.Timestamp) row.get("start_time")).toInstant();
                Instant end = ((java.sql.Timestamp) row.get("end_time")).toInstant();
                dirty.addAll(HealthConnectAggregator
                        .splitIntervalByLocalDay(start, end, (java.math.BigDecimal) row.get("value"), zone).keySet());
            } else {
                dirty.add(((java.sql.Date) row.get("local_date")).toLocalDate());
            }
            removed += jdbc.update("delete from health_connect_records"
                    + " where user_id=?::uuid and device_id=?::uuid and record_id=?", userId, deviceId, recordId);
        }
        return removed;
    }

    /**
     * Rebuilds each dirty day from the ledger and writes the derived provider rows.
     *
     * <p>Rebuilt, never adjusted. Every dirty day is recomputed from all remaining source records, so a
     * correction or deletion converges on the truth regardless of the order changes arrived in.
     *
     * <p>A day whose source records have all been deleted has its provider row <b>removed</b> rather
     * than left at zero. Zero is a real measurement and "no measurement" is not, and the Phase 10
     * canonical views deliberately treat NULL as absent. Writing 0 would invent a day nobody recorded.
     *
     * @return the number of days recomputed
     */
    private int recompute(UUID userId, UUID deviceId, Set<LocalDate> dirtyDays, ZoneId zone) {
        for (LocalDate day : dirtyDays) {
            recomputeActivity(userId, deviceId, day, zone);
            recomputeBody(userId, deviceId, day, zone);
        }
        return dirtyDays.size();
    }

    /** Rebuilds steps and active calories for one day. */
    private void recomputeActivity(UUID userId, UUID deviceId, LocalDate day, ZoneId zone) {
        Instant[] bounds = HealthConnectAggregator.dayBounds(day, zone);
        int steps = 0;
        boolean anySteps = false;
        java.math.BigDecimal calories = java.math.BigDecimal.ZERO;
        boolean anyCalories = false;

        for (Map<String, Object> row : overlapping(userId, deviceId, HealthConnectRecordType.STEPS, bounds)) {
            java.math.BigDecimal share = shareFor(row, day, zone);
            if (share != null) {
                steps += HealthConnectAggregator.toWholeNumber(share);
                // Presence, not magnitude, is what marks the day as measured. A share of zero is a
                // real reading of "no steps", so it must still count as data rather than as absence.
                anySteps = true;
            }
        }
        for (Map<String, Object> row
                : overlapping(userId, deviceId, HealthConnectRecordType.ACTIVE_CALORIES, bounds)) {
            java.math.BigDecimal share = shareFor(row, day, zone);
            if (share != null) {
                calories = calories.add(share);
                anyCalories = true;
            }
        }

        String providerRecordId = AGGREGATE_PREFIX + deviceId + ":" + day;
        if (!anySteps && !anyCalories) {
            // Every source record for this day is gone, so the derived row must go too, or the
            // canonical view would keep serving a total the source has explicitly withdrawn.
            jdbc.update("delete from daily_metrics where user_id=?::uuid and source=? and provider_record_id=?",
                    userId, PROVIDER, providerRecordId);
            return;
        }
        jdbc.update("insert into daily_metrics"
                        + "(id,user_id,metric_date,steps,calories_burned,source,provider_record_id,device_id)"
                        + " values (?::uuid,?::uuid,?::date,?,?,?,?,?::uuid)"
                        + " on conflict (user_id,source,provider_record_id) where provider_record_id is not null"
                        + " do update set steps=excluded.steps,calories_burned=excluded.calories_burned,"
                        + " metric_date=excluded.metric_date,device_id=excluded.device_id",
                UUID.randomUUID(), userId, day,
                anySteps ? steps : null, anyCalories ? HealthConnectAggregator.toWholeNumber(calories) : null,
                PROVIDER, providerRecordId, deviceId);
    }

    /**
     * Rebuilds weight and body fat for one day from the latest reading of each.
     *
     * <p>Selection, not summation: Health Connect never aggregates an instantaneous record to a total.
     * The latest reading by instant wins, with the record id as a deterministic tie-breaker so two
     * readings in the same second still resolve to a stable winner on a replay.
     *
     * <p>Weight is converted from kilograms to pounds exactly once, here. The ledger keeps kilograms so
     * a correction can still be checked against the original source value.
     */
    private void recomputeBody(UUID userId, UUID deviceId, LocalDate day, ZoneId zone) {
        Map<HealthConnectRecordType, Map<String, Object>> latest = new LinkedHashMap<>();
        for (HealthConnectRecordType type : List.of(HealthConnectRecordType.WEIGHT, HealthConnectRecordType.BODY_FAT)) {
            Instant[] bounds = HealthConnectAggregator.dayBounds(day, zone);
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "select record_id,value,start_time from health_connect_records"
                            + " where user_id=?::uuid and device_id=?::uuid and record_type=?"
                            + " and start_time>=? and start_time<?"
                            + " order by start_time asc, record_id asc",
                    userId, deviceId, type.wireName(),
                    java.sql.Timestamp.from(bounds[0]), java.sql.Timestamp.from(bounds[1]));
            if (!rows.isEmpty()) {
                latest.put(type, rows.get(rows.size() - 1));
            }
        }
        String providerRecordId = BODY_AGGREGATE_PREFIX + deviceId + ":" + day;
        if (latest.isEmpty()) {
            jdbc.update("delete from body_metrics where user_id=?::uuid and source=? and provider_record_id=?",
                    userId, PROVIDER, providerRecordId);
            return;
        }
        java.math.BigDecimal weightLb = null;
        java.math.BigDecimal bodyFatPct = null;
        Map<String, Object> weight = latest.get(HealthConnectRecordType.WEIGHT);
        if (weight != null) {
            // The single conversion point for Health Connect weight: kilograms in, pounds stored.
            weightLb = HealthNormalizer.kilogramsToPounds((java.math.BigDecimal) weight.get("value"));
        }
        Map<String, Object> bodyFat = latest.get(HealthConnectRecordType.BODY_FAT);
        if (bodyFat != null) {
            // A percentage in both systems, so it passes through unchanged.
            bodyFatPct = (java.math.BigDecimal) bodyFat.get("value");
        }
        jdbc.update("insert into body_metrics"
                        + "(id,user_id,metric_date,weight_lb,body_fat_pct,source,provider_record_id,device_id)"
                        + " values (?::uuid,?::uuid,?::date,?,?,?,?,?::uuid)"
                        + " on conflict (user_id,source,provider_record_id) where provider_record_id is not null"
                        + " do update set weight_lb=excluded.weight_lb,body_fat_pct=excluded.body_fat_pct,"
                        + " metric_date=excluded.metric_date,device_id=excluded.device_id",
                UUID.randomUUID(), userId, day, weightLb, bodyFatPct, PROVIDER, providerRecordId, deviceId);
    }

    /**
     * Source records of one type whose interval overlaps a day.
     *
     * <p>The predicate is {@code start < dayEnd AND end > dayStart}, not equality on
     * {@code local_date}: a record that began the previous day still contributes here, and an equality
     * test would silently drop it.
     */
    private List<Map<String, Object>> overlapping(UUID userId, UUID deviceId,
                                                   HealthConnectRecordType type, Instant[] bounds) {
        return jdbc.queryForList(
                "select record_id,value,start_time,end_time from health_connect_records"
                        + " where user_id=?::uuid and device_id=?::uuid and record_type=?"
                        + " and start_time<? and end_time>?",
                userId, deviceId, type.wireName(),
                java.sql.Timestamp.from(bounds[1]), java.sql.Timestamp.from(bounds[0]));
    }

    /**
     * How much of one source record lands on a day, re-derived from its own timestamps.
     *
     * <p>Recomputed rather than read from a stored column, so a corrected interval attributes correctly
     * to every day it touches instead of only to the day it originally started on.
     */
    private java.math.BigDecimal shareFor(Map<String, Object> row, LocalDate day, ZoneId zone) {
        Instant start = ((java.sql.Timestamp) row.get("start_time")).toInstant();
        Instant end = ((java.sql.Timestamp) row.get("end_time")).toInstant();
        java.math.BigDecimal value = (java.math.BigDecimal) row.get("value");
        return HealthConnectAggregator.splitIntervalByLocalDay(start, end, value, zone).get(day);
    }

    /**
     * Stores the client's own sync state (D11).
     *
     * <p>The changes token is written to {@code client_changes_token}, never to {@code sync_cursor}.
     * {@code sync_cursor} is the server's date watermark for the server-pull provider path; the
     * Android client owns a Health Connect changes token, which is opaque, platform-specific and has a
     * different lifetime. Conflating them would corrupt the pull path and would let a client steer the
     * server's own cursor.
     *
     * <p>The permission state is stored because the web UI must show it honestly (D10). It is
     * device-reported metadata only: nothing in the authorization path reads it, so it cannot grant or
     * deny access to anything.
     */
    private void persistClientState(UUID deviceId, UUID userId, HealthConnectBatch batch) {
        String permission = HealthConnectPermissions.canonical(batch.permissionStatus());
        jdbc.update("update health_devices set"
                        + " client_changes_token=coalesce(?,client_changes_token),"
                        + " permission_status=coalesce(?,permission_status),last_sync=now()"
                        + " where id=? and user_id=CAST(? as uuid)",
                usableToken(batch.changesToken()), permission, deviceId, userId);
    }

    /**
     * Bounds a client token to something storable.
     *
     * <p>The token is the platform's opaque resume handle. It is length-checked rather than parsed,
     * because FitTrack has no way to validate a platform-specific format and must not pretend to.
     */
    private static String usableToken(String token) {
        if (token == null || token.isBlank() || token.length() > 4_000) {
            return null;
        }
        return token;
    }


    /**
     * The user's stored zone, or null when they have never stated one.
     *
     * <p>Deliberately returns <b>null</b> rather than substituting a default. A user with no stored
     * zone has an undatable health history, and quietly filing a deletion under UTC would move a
     * day boundary for exactly the users who never chose a calendar. The caller already holds a
     * validated zone for the batch, so this is only a fallback for a ledger read.
     */
    private ZoneId storedZone(UUID userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select timezone from app_users where id=?::uuid", userId);
        Object stored = rows.isEmpty() ? null : rows.get(0).get("timezone");
        return stored == null ? null : UserTimezone.zoneId(String.valueOf(stored));
    }

    /** Scoped by id <em>and</em> user, like every other write in the health package. */
    private void markStatus(UUID deviceId, UUID userId, String status, String error) {
        jdbc.update("update health_devices set sync_status=?,last_error=?"
                        + " where id=? and user_id=CAST(? as uuid)", status, error, deviceId, userId);
    }

    /** Fetches the device only when it belongs to the caller. */
    private Map<String, Object> owned(UUID deviceId, UUID userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id,user_id,provider,external_device_id,sync_status from health_devices"
                        + " where id=? and user_id=CAST(? as uuid)", deviceId, userId);
        if (rows.isEmpty()) {
            throw new NoSuchElementException("Health device not found");
        }
        return rows.get(0);
    }
}
