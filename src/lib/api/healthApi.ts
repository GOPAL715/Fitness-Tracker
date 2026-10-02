import { apiClient, ApiError, json } from "./apiClient";

/**
 * Health connections.
 *
 * The backend serves these under /api/v1/health, so every call here uses that canonical prefix.
 * The previous client pointed at /health-devices, which has no route, so each of these requests
 * was a 404 and the whole health surface was unreachable.
 */

/** A device as the server exposes it. Internal sync state such as the cursor is not included. */
export type HealthDevice = {
  id: string;
  provider: string | null;
  external_device_id: string | null;
  device_name: string | null;
  device_type: string | null;
  status: string;
  sync_status: "idle" | "syncing" | "synced" | "error" | string;
  last_error: string | null;
  last_sync_at: string | null;
  awaiting_first_sync: boolean;
  /**
   * Phase 11: the permission state the Android bridge reported about itself.
   *
   * This is a DEVICE-REPORTED claim, not a server-verified fact. The backend cannot observe
   * Health Connect permissions, which are granted on the handset, so the UI must present it as
   * the bridge's own report. It never gates access to anything.
   */
  permission_status: HealthPermissionStatus | null;
};

/** The states a bridge may report, and which of them the server can actually establish. */
export type HealthPermissionStatus =
  | "connected"
  | "disconnected"
  | "syncing"
  | "sync_failed"
  | "permission_required"
  | "permission_revoked";

/**
 * True for the two states only the client can know.
 *
 * FitTrack has no way to verify them, so any message about them has to be phrased as a report
 * rather than as a fact. Presenting a client claim as a server-verified state would be the real
 * failure here.
 */
export function isClientReportedPermission(status: string | null | undefined): boolean {
  return status === "permission_required" || status === "permission_revoked";
}

export type HealthSyncResult = {
  status: string;
  activity_records: number;
  body_records: number;
  pages_fetched: number;
  /** Records dropped as technically invalid. Surfaced so a partial import is not implied complete. */
  records_rejected: number;
  /** True when the provider had more pages than one pass will follow. */
  truncated: boolean;
};

export type DisconnectResult = {
  disconnected: boolean;
  /** Imported history is deliberately kept; the server states this so the UI can as well. */
  imported_history_retained: boolean;
  message: string;
};

export const listHealthDevices = () => apiClient<HealthDevice[]>("/health/devices");

export const addHealthDevice = (payload: {
  device_name?: string;
  device_type?: string;
  provider?: string;
  external_device_id?: string;
}) => apiClient<HealthDevice>("/health/devices", { method: "POST", ...json(payload) });

export const syncHealthDevice = (id: string, to?: string) =>
  apiClient<HealthSyncResult>(`/health/devices/${id}/sync`, {
    method: "POST",
    ...json(to ? { to } : {}),
  });

export const deleteHealthDevice = (id: string) =>
  apiClient<DisconnectResult>(`/health/devices/${id}`, { method: "DELETE" });

/**
 * Turns a health failure into a message worth showing.
 *
 * Disconnect is not an error path, so a 404 there means the connection is already gone rather
 * than something the user did wrong.
 */
export function healthErrorMessage(error: unknown, action: "sync" | "connect" | "disconnect" = "sync"): string {
  if (!(error instanceof ApiError)) {
    return error instanceof Error ? error.message : "Something went wrong. Please try again.";
  }
  switch (error.status) {
    case 400:
      return "That request was not valid. Please try again.";
    case 401:
      return "Please sign in again to manage health connections.";
    case 404:
      return action === "disconnect"
        ? "That connection no longer exists."
        : "That health connection was not found.";
    case 409:
      // A replayed request: the work already happened, so asking again with a fresh key is the
      // remedy, not retrying the same one.
      return "That sync was already processed. Run sync again to fetch the latest data.";
    case 429:
      return "You have reached the sync request limit. Wait a moment and try again.";
    case 502:
      return "The health provider could not be reached. Previously synced data is unchanged.";
    default:
      return error.message || "Something went wrong. Please try again.";
  }
}

/** A short, plain description of a stored sync state for display. */
export function syncStateLabel(device: {
  sync_status?: string | null;
  last_error?: string | null;
  awaiting_first_sync?: boolean;
}): { label: string; tone: string } {
  if (device.sync_status === "syncing") return { label: "Syncing", tone: "#38bdf8" };
  if (device.sync_status === "error") {
    return { label: "Last sync failed", tone: "#f87171" };
  }
  if (device.sync_status === "synced") return { label: "Up to date", tone: "#4ade80" };
  if (device.awaiting_first_sync) return { label: "Connected, not synced yet", tone: "#fbbf24" };
  return { label: "Connected", tone: "#38bdf8" };
}

/**
 * Plain-language retention notice shown beside a disconnect action.
 *
 * The server keeps imported history, so the UI must not imply that disconnecting erases it.
 */
/**
 * A short description of the permission state the Android bridge reported about itself (D10).
 *
 * <p>Returning `null` means there is nothing to say: a device from a web-reachable provider has no
 * Health Connect permissions to report, so the UI shows nothing rather than inventing a state.
 *
 * <p>Every message for the two client-reported states is phrased as something the device *says*,
 * never as a fact FitTrack established. The backend cannot observe Health Connect permissions, so
 * wording these as verified would misrepresent what the server actually knows.
 */
export function permissionStateNotice(status: string | null | undefined): string | null {
  switch (status) {
    case "permission_required":
      return "The Android app reports that Health Connect permission is needed before it can read your data. Open the app and grant access, then it will sync.";
    case "permission_revoked":
      return "The Android app reports that Health Connect permission was revoked. Your previously imported data is unchanged. Reopen the app to grant access again.";
    case "connected":
      return "The Android app reports that Health Connect access is granted.";
    default:
      return null;
  }
}

/** A short badge label for a reported permission state, or null when there is nothing to report. */
export function permissionStateLabel(status: string | null | undefined): string | null {
  switch (status) {
    case "permission_required":
      return "Permission needed";
    case "permission_revoked":
      return "Permission revoked";
    case "connected":
      return "Access granted";
    default:
      return null;
  }
}
export const DISCONNECT_RETENTION_NOTICE =
  "Disconnecting stops future syncing. Previously imported health data remains in AI FitTrack.";
