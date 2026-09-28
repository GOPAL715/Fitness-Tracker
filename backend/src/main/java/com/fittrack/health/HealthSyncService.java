package com.fittrack.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/**
 * Imports provider records into the user's own rows, idempotently.
 *
 * <p>Every write is scoped by {@code user_id} taken from the authenticated caller, and every
 * status write is scoped by {@code id} <em>and</em> {@code user_id}. A provider record id is only
 * ever a de-duplication key within that user's rows, never an authorization token.
 *
 * <h2>Sources coexist (D1)</h2>
 * A day may hold a manual row and one row per connected device. Nothing is overwritten: the
 * upsert targets {@code (user_id, source, provider_record_id)}, and which value a reader sees is
 * decided once, by the canonical views, using a documented per-field policy.
 */
@Service
public class HealthSyncService {

    private static final Logger log = LoggerFactory.getLogger(HealthSyncService.class);

    /**
     * Upper bound on pages fetched in one pass.
     *
     * <p>Pagination used to be a lie: the cursor was stored and never followed. Following it
     * unbounded is its own failure mode, so the loop is capped and reports when it stops early.
     */
    static final int MAX_PAGES = 20;

    private final JdbcTemplate jdbc;
    private final HealthProvider provider;
    private final int windowDays;

    public HealthSyncService(JdbcTemplate jdbc, HealthProvider provider,
            @org.springframework.beans.factory.annotation.Value("${app.health-sync.window-days:30}") int windowDays) {
        this.jdbc = jdbc;
        this.provider = provider;
        this.windowDays = Math.max(1, windowDays);
    }

    /** Outcome of one synchronization pass. */
    public record SyncResult(int activityWritten, int bodyWritten, String syncStatus,
                             String errorCategory, int pagesFetched, int recordsRejected,
                             boolean truncated) {
        public boolean ok() { return "synced".equals(syncStatus); }
    }

    /**
     * Synchronizes one device owned by the caller.
     *
     * <p>Incremental when the device already carries a cursor, otherwise a bounded window ending
     * on {@code to}. An unbounded "download all history" fetch is never performed.
     */
    @Transactional
    public SyncResult sync(UUID deviceId, UUID userId, LocalDate to) {
        Map<String, Object> device = owned(deviceId, userId);
        String storedCursor = (String) device.get("sync_cursor");
        LocalDate from = storedCursor == null || storedCursor.isBlank()
                ? to.minusDays(windowDays - 1L)
                : parseCursorDate(storedCursor, to);
        String providerKey = String.valueOf(device.get("provider"));

        markStatus(deviceId, userId, "syncing", null);
        int activity = 0;
        int body = 0;
        int pages = 0;
        int rejected = 0;
        // A cursor the provider has already handed back would loop forever, so every cursor seen is
        // remembered and a repeat is treated as the end of the sequence.
        Set<String> seenCursors = new HashSet<>();
        boolean truncated = false;

        try {
            // The persisted cursor is a date watermark; the provider's pagination token is separate
            // and starts empty on every pass. Reusing the watermark as a resume token would hand a
            // date to a provider expecting its own opaque token, and storing the provider's offset as
            // the watermark would make the next pass skip records the provider corrected in place.
            String pageCursor = null;
            while (true) {
                if (pages >= MAX_PAGES) {
                    // Report honestly rather than implying a complete import.
                    truncated = true;
                    log.info("health_sync_page_limit device_id={} pages={}", deviceId, pages);
                    break;
                }
                HealthProvider.Batch batch = provider.fetch(from, to, pageCursor);
                pages++;

                HealthRecordValidator.Result checked =
                        HealthRecordValidator.validate(batch.activity(), batch.body());
                rejected += checked.rejected();

                activity += persistActivity(userId, providerKey, deviceId, checked.activity());
                body += persistBody(userId, providerKey, deviceId, checked.body());

                String next = HealthRecordValidator.usableCursor(batch.nextCursor());
                if (next == null || next.equals(pageCursor) || !seenCursors.add(next)) {
                    // No cursor, a repeat of the current one, or one already seen: the sequence has
                    // ended. A provider that echoes its input cursor cannot drive an endless loop.
                    break;
                }
                pageCursor = next;
            }

            // The watermark is the day the pass covered, so the next pass resumes from here. It is
            // only advanced on success; a failed pass re-reads the same window idempotently.
            finishSync(deviceId, userId, to.toString());
            return new SyncResult(activity, body, "synced", null, pages, rejected, truncated);
        } catch (HealthProviderException e) {
            // The category is stored; the provider message is logged, never returned.
            log.info("health_sync_failed device_id={} category={} pages={}", deviceId, e.category(), pages);
            markStatus(deviceId, userId, "error", e.category());
            // Pages already written stay written: partial progress is kept, and the cursor is not
            // advanced, so the next attempt re-reads the same window idempotently.
            return new SyncResult(activity, body, "error", e.category(), pages, rejected, truncated);
        }
    }

    /**
     * A stored cursor is a date watermark, but a provider may also store an opaque token here.
     * Anything unparseable falls back to the bounded window rather than throwing.
     */
    private LocalDate parseCursorDate(String cursor, LocalDate to) {
        try {
            LocalDate parsed = LocalDate.parse(cursor);
            return parsed.isAfter(to) ? to.minusDays(windowDays - 1L) : parsed;
        } catch (RuntimeException e) {
            return to.minusDays(windowDays - 1L);
        }
    }

    /**
     * Inserts or updates activity records in one JDBC batch.
     *
     * <p>{@code ON CONFLICT} targets the provider-record partial unique index, so replaying the
     * same provider record updates the existing row instead of adding a second one, and never
     * touches a manual row for the same day.
     */
    private int persistActivity(UUID userId, String providerKey, UUID deviceId,
                                List<HealthProvider.ActivityRecord> records) {
        if (records.isEmpty()) return 0;
        int[] outcome = jdbc.batchUpdate(
                "insert into daily_metrics(id,user_id,metric_date,steps,active_minutes,calories_burned,"
                        + "source,provider_record_id,device_id) values (?,?::uuid,?,?,?,?,?,?,?)"
                        + " on conflict (user_id,source,provider_record_id) where provider_record_id is not null"
                        + " do update set steps=excluded.steps,active_minutes=excluded.active_minutes,"
                        + " calories_burned=excluded.calories_burned,device_id=excluded.device_id",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        HealthProvider.ActivityRecord r = records.get(i);
                        ps.setObject(1, UUID.randomUUID());
                        ps.setObject(2, userId);
                        ps.setDate(3, Date.valueOf(r.date()));
                        ps.setInt(4, r.steps());
                        ps.setLong(5, r.activeMinutes());
                        ps.setLong(6, r.caloriesBurned());
                        ps.setString(7, providerKey);
                        ps.setString(8, r.recordId());
                        ps.setObject(9, deviceId);
                    }

                    @Override
                    public int getBatchSize() { return records.size(); }
                });
        return total(outcome);
    }

    private int persistBody(UUID userId, String providerKey, UUID deviceId,
                            List<HealthProvider.BodyRecord> records) {
        if (records.isEmpty()) return 0;
        int[] outcome = jdbc.batchUpdate(
                "insert into body_metrics(id,user_id,metric_date,weight_lb,body_fat_pct,source,provider_record_id,device_id)"
                        + " values (?,?::uuid,?,?,?,?,?,?)"
                        + " on conflict (user_id,source,provider_record_id) where provider_record_id is not null"
                        + " do update set weight_lb=excluded.weight_lb,body_fat_pct=excluded.body_fat_pct,"
                        + " device_id=excluded.device_id",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        HealthProvider.BodyRecord r = records.get(i);
                        ps.setObject(1, UUID.randomUUID());
                        ps.setObject(2, userId);
                        ps.setDate(3, Date.valueOf(r.date()));
                        ps.setBigDecimal(4, r.weightLb());
                        ps.setBigDecimal(5, r.bodyFatPct());
                        ps.setString(6, providerKey);
                        ps.setString(7, r.recordId());
                        ps.setObject(8, deviceId);
                    }

                    @Override
                    public int getBatchSize() { return records.size(); }
                });
        return total(outcome);
    }

    /**
     * Counts rows affected by a batch.
     *
     * <p>SUCCESS_NO_INFO (-2) means the driver could not report a count, which still means the
     * statement was sent, so it counts as one written row rather than being dropped from the total.
     */
    private static int total(int[] outcome) {
        int sum = 0;
        for (int value : outcome) {
            if (value > 0 || value == java.sql.Statement.SUCCESS_NO_INFO) sum++;
        }
        return sum;
    }

    /**
     * Marks the sync finished.
     *
     * <p>Scoped by id and user together, like every other write here.
     */
    private void finishSync(UUID deviceId, UUID userId, String watermark) {
        jdbc.update("update health_devices set sync_status='synced',last_sync=?,sync_cursor=?,last_error=null"
                        + " where id=? and user_id=CAST(? as uuid)",
                Date.from(Instant.now()), watermark, deviceId, userId);
    }

    /**
     * Records an intermediate or failure status.
     *
     * <p>The {@code user_id} predicate is not redundant with the {@link #owned} lookup. Every other
     * write in this class carries it, and a status write that silently omits it would let a future
     * caller mark another user's device as syncing or failed.
     */
    private void markStatus(UUID deviceId, UUID userId, String status, String error) {
        jdbc.update("update health_devices set sync_status=?,last_error=? where id=? and user_id=CAST(? as uuid)",
                status, error, deviceId, userId);
    }

    /** Fetches the device only when it belongs to the caller. */
    private Map<String, Object> owned(UUID deviceId, UUID userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id,user_id,device_name,device_type,status,provider,external_device_id,"
                        + "sync_cursor,last_sync,sync_status from health_devices"
                        + " where id=? and user_id=CAST(? as uuid)", deviceId, userId);
        if (rows.isEmpty()) throw new NoSuchElementException("Health device not found");
        return rows.get(0);
    }

    public HealthProvider provider() { return provider; }

    public int windowDays() { return windowDays; }
}
