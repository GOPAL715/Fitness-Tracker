package com.fittrack.health;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code GET /api/v1/health/integrations} - the provider catalogue, merged with the caller's own
 * connections (Phase 20).
 *
 * <h2>Why this endpoint exists</h2>
 * The list of health providers used to be a hardcoded array in the browser, while the authoritative
 * allowlist lived in the backend. Nothing kept the two in step, so a client could be shown a provider
 * the server would reject on registration. This route serves the server's own description, so the
 * browser renders what the server believes rather than a second opinion it has to keep current.
 *
 * <h2>Why it is merged rather than two calls</h2>
 * A client asking "what can I connect, and what have I connected" should not have to correlate two
 * responses, and a correlation performed on the client is exactly where a provider the server would
 * reject could slip through. The merge happens here, on the server, against the principal.
 *
 * <h2>Security</h2>
 * <ul>
 *   <li>Ownership comes from the JWT alone. There is no user id in the path or the query, so there is
 *       nothing for a caller to substitute.</li>
 *   <li>The connection read is scoped by {@code user_id} in the predicate rather than filtered
 *       afterwards, so another user's rows are never read and therefore never rendered.</li>
 *   <li>The response is built from {@link HealthIntegrationResponse} and {@link HealthDeviceResponse},
 *       both allowlists. {@code sync_cursor} and {@code client_changes_token} are not selected, and no
 *       credential exists in the schema to select in the first place.</li>
 *   <li>The in-process test provider is filtered out, so a browser is never offered an integration
 *       that reaches no external service.</li>
 * </ul>
 *
 * <p>This route is a read. Connecting, syncing and disconnecting stay on the existing
 * {@code /api/v1/health/devices} routes, which already carry their own validation and ownership
 * checks. Duplicating them here would have produced two contracts for one action.
 */
@RestController
@RequestMapping("/api/v1/health")
public class HealthIntegrationController {

    /**
     * The columns read for this route.
     *
     * <p>Explicit rather than {@code SELECT *} for the same reason {@code HealthSyncController} names
     * its columns: an unnamed column is a published column. {@code sync_cursor} steers the next sync
     * window and {@code client_changes_token} belongs to the Android client; neither is the browser's
     * business, and {@code docs/database.md} already promises the cursor is never returned.
     */
    private static final String CONNECTION_COLUMNS =
            "id,device_name,device_type,status,provider,external_device_id,last_sync,sync_status,last_error,"
                    + "permission_status";

    private final JdbcTemplate jdbc;

    public HealthIntegrationController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/integrations")
    public Map<String, Object> integrations(@AuthenticationPrincipal String principal) {
        UUID owner = uuid(principal);
        Map<String, List<HealthDeviceResponse>> byProvider = loadConnections(owner);
        List<HealthIntegrationResponse> integrations = new ArrayList<>();
        for (HealthProviderCatalog.ProviderDescriptor descriptor : HealthProviderCatalog.clientVisible()) {
            List<HealthDeviceResponse> connections = byProvider.getOrDefault(descriptor.key(), List.of());
            integrations.add(new HealthIntegrationResponse(
                    descriptor.key(),
                    descriptor.label(),
                    descriptor.availability().wire(),
                    descriptor.authModel(),
                    descriptor.credentialModel(),
                    descriptor.supportedMetrics(),
                    descriptor.connectable(),
                    descriptor.boundary(),
                    HealthConnectionState.aggregate(
                            connections.stream().map(HealthDeviceResponse::connectionState).toList()),
                    connections));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("integrations", integrations);
        return body;
    }

    /**
     * This user's connections, grouped by provider.
     *
     * <p>A row whose provider is not in the visible catalogue is still returned to its owner through
     * {@code /health/devices}; it is simply not merged into an integration entry. Hiding it from its
     * own owner would be worse than leaving it where it already appears, and the catalogue describes
     * providers - it is not a filter on ownership.
     */
    private Map<String, List<HealthDeviceResponse>> loadConnections(UUID owner) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select " + CONNECTION_COLUMNS + " from health_devices"
                        + " where user_id=CAST(? AS uuid) order by device_name, id", owner);
        Map<String, List<HealthDeviceResponse>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object provider = row.get("provider");
            if (provider == null) {
                continue;
            }
            grouped.computeIfAbsent(String.valueOf(provider), key -> new ArrayList<>())
                    .add(HealthDeviceResponse.from(row));
        }
        return grouped;
    }

    private static UUID uuid(String principal) {
        try {
            return UUID.fromString(principal);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }
}
