package com.fittrack.health;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The client-facing view of a health connection.
 *
 * <h2>Why a DTO exists</h2>
 * The endpoints previously returned raw {@code Map} rows straight out of {@code health_devices}.
 * That is convenient until someone adds a column: {@code sync_cursor} was being sent to the
 * browser, and any future column holding a credential would be shipped the same way. This type is
 * an allowlist, so a column that should not be visible is not visible because it was never named.
 *
 * <p>Deliberately absent: {@code sync_cursor}, {@code user_id}, any token or credential field, and
 * the provider's internal identifiers beyond the opaque external device id the user supplied.
 *
 * <h2>permissionStatus (Phase 20)</h2>
 * The V11 column existed, was written by every Health Connect ingest, was declared in the frontend
 * types and was rendered by the profile screen - but this record had no field for it and
 * {@code HealthSyncController} never selected it, so the value was structurally unreachable and the
 * permission badge could never appear. Phase 20 completes the contract rather than adding a feature.
 *
 * <p>It remains a <b>device-reported claim</b>. The server cannot observe Android Health Connect
 * permissions, so nothing in the authentication or authorization path reads this field and it can
 * neither grant nor deny access to a record. It is presentation metadata, and the UI is required to
 * label it as something the app reported.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record HealthDeviceResponse(
        UUID id,
        String provider,
        String externalDeviceId,
        String deviceName,
        String deviceType,
        /** Connected state as the product models it. */
        String status,
        /** idle, syncing, synced or error. */
        String syncStatus,
        /** Stable machine-readable failure category, or null when the last sync succeeded. */
        String lastError,
        Instant lastSyncAt,
        /** True when a device is connected but has never completed a sync. */
        boolean awaitingFirstSync,
        /**
         * What the device reported about its own platform permissions, or null when it reported
         * nothing. Never an authorization input.
         */
        String permissionStatus) {

    public HealthDeviceResponse withStatus(String newStatus) {
        return new HealthDeviceResponse(id, provider, externalDeviceId, deviceName, deviceType,
                newStatus, syncStatus, lastError, lastSyncAt, awaitingFirstSync, permissionStatus);
    }

    /** The one state a client should render for this connection. */
    public String connectionState() {
        return HealthConnectionState.of(syncStatus);
    }

    /**
     * Projects a stored row onto the response contract.
     *
     * <p>Only the allowlisted columns are read. {@code sync_cursor} is deliberately never selected,
     * so it cannot be leaked by a later change to this method.
     */
    @SuppressWarnings("unchecked")
    public static HealthDeviceResponse from(Map<String, Object> row) {
        Instant lastSync = null;
        Object raw = row.get("last_sync");
        if (raw instanceof Instant i) {
            lastSync = i;
        } else if (raw instanceof java.sql.Timestamp t) {
            lastSync = t.toInstant();
        }
        String syncStatus = row.get("sync_status") == null ? "idle" : String.valueOf(row.get("sync_status"));
        return new HealthDeviceResponse(
                (UUID) row.get("id"),
                row.get("provider") == null ? null : String.valueOf(row.get("provider")),
                row.get("external_device_id") == null ? null : String.valueOf(row.get("external_device_id")),
                row.get("device_name") == null ? null : String.valueOf(row.get("device_name")),
                row.get("device_type") == null ? null : String.valueOf(row.get("device_type")),
                row.get("status") == null ? "Connected" : String.valueOf(row.get("status")),
                syncStatus,
                row.get("last_error") == null ? null : String.valueOf(row.get("last_error")),
                lastSync,
                lastSync == null,
                row.get("permission_status") == null ? null : String.valueOf(row.get("permission_status")));
    }
}
