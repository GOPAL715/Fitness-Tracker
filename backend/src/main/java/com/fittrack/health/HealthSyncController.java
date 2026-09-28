package com.fittrack.health;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Health device registration and synchronization.
 *
 * <p>Every route resolves the device through the authenticated user, so a provider record id can
 * never reach another user's connection. Responses are built from
 * {@link HealthDeviceResponse} rather than raw rows, so internal sync state never reaches the
 * client. Error responses carry only a stable category.
 *
 * <h2>Retention on disconnect (D3)</h2>
 * Disconnecting removes the connection and stops future syncing. Imported history is deliberately
 * <b>kept</b>: {@code daily_metrics} and {@code body_metrics} reference the device with
 * {@code ON DELETE SET NULL}, so deleting the connection drops only the pointer. The response says
 * so explicitly, because claiming a deletion that did not happen would be worse than silence.
 */
@RestController
@RequestMapping("/api/v1/health")
public class HealthSyncController {

    /** The only columns the list endpoint may read. */
    private static final String DEVICE_COLUMNS =
            "id,device_name,device_type,status,provider,external_device_id,last_sync,sync_status,last_error";

    private final JdbcTemplate jdbc;
    private final HealthSyncService sync;

    public HealthSyncController(JdbcTemplate jdbc, HealthSyncService sync) {
        this.jdbc = jdbc;
        this.sync = sync;
    }

    @GetMapping("/devices")
    public List<HealthDeviceResponse> devices(@AuthenticationPrincipal String user) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select " + DEVICE_COLUMNS + " from health_devices"
                        + " where user_id=CAST(? AS uuid) order by id desc", uuid(user));
        return rows.stream().map(HealthDeviceResponse::from).toList();
    }

    /** Registers a provider connection owned by the caller. */
    @PostMapping("/devices")
    public HealthDeviceResponse register(@RequestBody Map<String, Object> body,
                                         @AuthenticationPrincipal String user) {
        UUID owner = uuid(user);
        if (body.containsKey("user_id")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "user_id is server controlled");
        }
        for (String forbidden : List.of("access_token", "refresh_token", "client_secret", "token",
                "sync_cursor")) {
            if (body.containsKey(forbidden)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "not an accepted field");
            }
        }
        String name = String.valueOf(body.getOrDefault("device_name", "Wearable"));
        // The provider must be one this build recognises. An arbitrary string would let a client
        // label a connection as any integration it liked, and would create a row that no pipeline
        // can ever attribute. The default is resolved server-side, never taken from the client.
        Object supplied = body.get("provider");
        String provider;
        if (supplied == null) {
            // Absent means "use the default", decided here and never by the client.
            provider = HealthProviders.canonical(sync.provider().key());
            if (provider == null) provider = HealthProviders.FALLBACK;
        } else {
            provider = HealthProviders.canonical(String.valueOf(supplied));
            if (provider == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "provider must be one of " + HealthProviders.SUPPORTED);
            }
        }
        String externalId = body.get("external_device_id") == null
                ? null : String.valueOf(body.get("external_device_id"));

        // The conflict target repeats the partial index predicate so PostgreSQL can match it.
        var params = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("user_id", owner)
                .addValue("device_name", name)
                .addValue("device_type", String.valueOf(body.getOrDefault("device_type", "wearable")))
                .addValue("provider", provider)
                .addValue("external_device_id", externalId);
        Map<String, Object> row = new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc)
                .queryForMap(
                        "insert into health_devices(id,user_id,device_name,device_type,status,"
                                + "provider,external_device_id,sync_status)"
                                + " values (:id,:user_id,:device_name,:device_type,'Connected',:provider,"
                                + ":external_device_id,'idle')"
                                + " on conflict (user_id,provider,external_device_id) where external_device_id is not null"
                                + " do update set device_name=excluded.device_name"
                                + " returning " + DEVICE_COLUMNS, params);
        return HealthDeviceResponse.from(row);
    }

    /**
     * Runs a synchronization pass.
     *
     * <p>Returns 502 with a stable category when the provider fails; the provider's own message
     * and any credentials are never echoed back.
     */
    @PostMapping("/devices/{id}/sync")
    public Map<String, Object> syncDevice(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body,
                                          @AuthenticationPrincipal String user) {
        UUID owner = uuid(user);
        LocalDate to;
        try {
            to = body != null && body.get("to") != null
                    ? LocalDate.parse(String.valueOf(body.get("to")))
                    : LocalDate.now();
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "to must be an ISO date");
        }
        HealthSyncService.SyncResult result;
        try {
            result = sync.sync(id, owner, to);
        } catch (java.util.NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Health device not found");
        }
        if (!result.ok()) {
            HttpStatus status = "auth".equals(result.errorCategory())
                    ? HttpStatus.UNAUTHORIZED
                    : HttpStatus.BAD_GATEWAY;
            throw new ResponseStatusException(status, "Health provider " + result.errorCategory());
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("status", result.syncStatus());
        out.put("activity_records", result.activityWritten());
        out.put("body_records", result.bodyWritten());
        out.put("pages_fetched", result.pagesFetched());
        // Surfaced so a partial import is visible rather than implied complete.
        out.put("records_rejected", result.recordsRejected());
        out.put("truncated", result.truncated());
        return out;
    }

    /**
     * Removes the connection and stops future syncing.
     *
     * <p>Imported history is kept (D3). The response says so rather than implying a deletion.
     */
    @DeleteMapping("/devices/{id}")
    public Map<String, Object> disconnect(@PathVariable UUID id, @AuthenticationPrincipal String user) {
        UUID owner = uuid(user);
        int removed = jdbc.update("delete from health_devices where id=? and user_id=CAST(? AS uuid)", id, owner);
        if (removed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Health device not found");
        return Map.of(
                "disconnected", true,
                "imported_history_retained", true,
                "message", "Disconnecting stops future syncing. Previously imported health data"
                        + " remains in FitTrack.");
    }

    private static UUID uuid(String principal) {
        try {
            return UUID.fromString(principal);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }
}
