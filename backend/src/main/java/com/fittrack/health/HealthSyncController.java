package com.fittrack.health;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Health device registration and synchronization.
 *
 * <p>Every route resolves the device through the authenticated user, so a provider record id can
 * never be used to reach another user's connection. Error responses carry only a stable category.
 */
@RestController
@RequestMapping("/api/v1/health")
public class HealthSyncController {

    private final JdbcTemplate jdbc;
    private final HealthSyncService sync;

    public HealthSyncController(JdbcTemplate jdbc, HealthSyncService sync) {
        this.jdbc = jdbc;
        this.sync = sync;
    }

    @GetMapping("/devices")
    public List<Map<String, Object>> devices(@AuthenticationPrincipal String user) {
        return jdbc.queryForList("select id,device_name,device_type,status,provider,external_device_id,"
                        + "last_sync,sync_status,sync_cursor from health_devices"
                        + " where user_id=CAST(? AS uuid) order by id desc", uuid(user));
    }

    /** Registers a provider connection owned by the caller. */
    @PostMapping("/devices")
    public Map<String, Object> register(@RequestBody Map<String, Object> body,
                                        @AuthenticationPrincipal String user) {
        UUID owner = uuid(user);
        if (body.containsKey("user_id")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "user_id is server controlled");
        }
        for (String forbidden : List.of("access_token", "refresh_token", "client_secret", "token")) {
            if (body.containsKey(forbidden)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "provider credentials are not stored");
            }
        }
        String name = String.valueOf(body.getOrDefault("device_name", "Wearable"));
        String provider = String.valueOf(body.getOrDefault("provider", sync.provider().key()));
        String externalId = body.get("external_device_id") == null ? null : String.valueOf(body.get("external_device_id"));

        // The conflict target repeats the partial index predicate so PostgreSQL can match it.
        var params = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("user_id", owner)
                .addValue("device_name", name)
                .addValue("device_type", String.valueOf(body.getOrDefault("device_type", "wearable")))
                .addValue("provider", provider)
                .addValue("external_device_id", externalId);
        return new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc).queryForMap(
                "insert into health_devices(id,user_id,device_name,device_type,status,"
                        + "provider,external_device_id,sync_status)"
                        + " values (:id,:user_id,:device_name,:device_type,'Connected',:provider,"
                        + ":external_device_id,'idle')"
                        + " on conflict (user_id,provider,external_device_id) where external_device_id is not null"
                        + " do update set device_name=excluded.device_name returning *", params);
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
        LocalDate to = body != null && body.get("to") != null
                ? LocalDate.parse(String.valueOf(body.get("to")))
                : LocalDate.now();
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
        return Map.of("status", result.syncStatus(), "activity_records", result.activityWritten(),
                "body_records", result.bodyWritten());
    }

    @DeleteMapping("/devices/{id}")
    public Map<String, Object> disconnect(@PathVariable UUID id, @AuthenticationPrincipal String user) {
        UUID owner = uuid(user);
        int removed = jdbc.update("delete from health_devices where id=? and user_id=CAST(? AS uuid)", id, owner);
        if (removed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Health device not found");
        return Map.of("disconnected", true);
    }

    private static UUID uuid(String principal) {
        try {
            return UUID.fromString(principal);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }
}
