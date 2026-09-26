package com.fittrack.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Imports provider records into the user's own rows, idempotently.
 *
 * <p>Every write is scoped by {@code user_id} taken from the authenticated caller. A provider
 * record id is only ever a de-duplication key within that user's rows - never an authorization
 * token. Replaying the same provider record updates the existing row instead of adding a second.
 */
@Service
public class HealthSyncService {

    private static final Logger log = LoggerFactory.getLogger(HealthSyncService.class);

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
    public record SyncResult(int activityWritten, int bodyWritten, String syncStatus, String errorCategory) {
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
        String cursor = (String) device.get("sync_cursor");
        LocalDate from = cursor == null || cursor.isBlank()
                ? to.minusDays(windowDays - 1L)
                : LocalDate.parse(cursor);
        String providerKey = String.valueOf(device.get("provider"));

        markStatus(deviceId, "syncing", null);
        try {
            HealthProvider.Batch batch = provider.fetch(from, to, cursor);
            int activity = 0;
            int body = 0;
            for (HealthProvider.ActivityRecord record : batch.activity()) {
                upsertActivity(userId, providerKey, record);
                activity++;
            }
            for (HealthProvider.BodyRecord record : batch.body()) {
                upsertBody(userId, providerKey, record);
                body++;
            }
            String watermark = batch.nextCursor() != null ? batch.nextCursor() : to.toString();
            jdbc.update("update health_devices set sync_status='synced',last_sync=?,sync_cursor=?,last_error=null"
                            + " where id=? and user_id=CAST(? as uuid)",
                    Date.from(Instant.now()), watermark, deviceId, userId);
            return new SyncResult(activity, body, "synced", null);
        } catch (HealthProviderException e) {
            // The category is stored; the provider message is logged, never returned.
            log.info("health_sync_failed device_id={} category={}", deviceId, e.category());
            markStatus(deviceId, "error", e.category());
            return new SyncResult(0, 0, "error", e.category());
        }
    }

    /**
     * Inserts or updates one activity record.
     *
     * <p>{@code ON CONFLICT} targets the provider-record unique index, so replaying the same
     * provider record can never create a duplicate row.
     */
    private void upsertActivity(UUID userId, String providerKey, HealthProvider.ActivityRecord record) {
        jdbc.update("insert into daily_metrics(id,user_id,metric_date,steps,active_minutes,calories_burned,"
                        + "source,provider_record_id) values (?,?::uuid,?,?,?,?,?,?)"
                        + " on conflict (user_id,source,provider_record_id) where provider_record_id is not null"
                        + " do update set steps=excluded.steps,active_minutes=excluded.active_minutes,"
                        + " calories_burned=excluded.calories_burned",
                UUID.randomUUID(), userId, Date.valueOf(record.date()), record.steps(),
                record.activeMinutes(), record.caloriesBurned(), providerKey, record.recordId());
    }

    private void upsertBody(UUID userId, String providerKey, HealthProvider.BodyRecord record) {
        jdbc.update("insert into body_metrics(id,user_id,metric_date,weight_lb,body_fat_pct,source,provider_record_id)"
                        + " values (?,?::uuid,?,?,?,?,?)"
                        + " on conflict (user_id,source,provider_record_id) where provider_record_id is not null"
                        + " do update set weight_lb=excluded.weight_lb,body_fat_pct=excluded.body_fat_pct",
                UUID.randomUUID(), userId, Date.valueOf(record.date()),
                record.weightLb(), record.bodyFatPct(), providerKey, record.recordId());
    }

    private void markStatus(UUID deviceId, String status, String error) {
        jdbc.update("update health_devices set sync_status=?,last_error=? where id=?",
                status, error, deviceId);
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
