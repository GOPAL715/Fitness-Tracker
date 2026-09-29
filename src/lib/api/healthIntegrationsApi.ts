import { apiClient, json } from "./apiClient";

/**
 * The health integration surface (Phase 20).
 *
 * <p>The provider catalogue is served by the backend rather than hardcoded here. It used to live in
 * `src/lib/healthProviders.ts`, which meant the list a user saw and the list the server would accept
 * were two independent opinions; a provider added to one and not the other would be offered to
 * someone and then refused on connection. The server is now the single source of truth and this
 * module only describes its response.
 *
 * <p>No credential ever arrives here. `credential_model` is a statement that FitTrack holds none, and
 * the only identifiers in a connection are the ones the user supplied plus a row id.
 */

/** How a provider can be reached from this deployment. */
export type ProviderAvailability =
  | "native_bridge"
  | "server_credentials_required"
  | "native_app_required"
  | "manual";

/**
 * The five states a connection can be in, all derived server-side from what is stored.
 *
 * `connecting` means registered but never synced - the connection exists but has not yet produced
 * data, which is what the previous "Connected, not synced yet" label was already describing.
 */
export type ConnectionState =
  | "disconnected"
  | "connecting"
  | "connected"
  | "syncing"
  | "sync_failed";

export const CONNECTION_STATES: ConnectionState[] = [
  "disconnected",
  "connecting",
  "connected",
  "syncing",
  "sync_failed",
];

/** A connection as the server exposes it. Internal state such as the cursor is never included. */
export type IntegrationConnection = {
  id: string;
  provider: string | null;
  external_device_id: string | null;
  device_name: string | null;
  device_type: string | null;
  status: string;
  sync_status: string;
  last_error: string | null;
  last_sync_at: string | null;
  awaiting_first_sync: boolean;
  /**
   * What the device reported about its own platform permissions, or null when it reported nothing.
   *
   * This is a DEVICE-REPORTED claim, not a server-verified fact: the backend cannot observe Android
   * Health Connect permissions, which are granted on the handset. It never gates access to anything.
   */
  permission_status: string | null;
};

export type HealthIntegration = {
  provider: string;
  label: string;
  availability: ProviderAvailability;
  /** How a connection is established, in plain words rather than a protocol name. */
  auth_model: string;
  /** Always "none" in this build: FitTrack holds no provider token, refresh token or client secret. */
  credential_model: string;
  supported_metrics: string[];
  /** Whether a connection can be created for this provider at all. */
  connectable: boolean;
  /** The honest explanation of what reaching this provider would require. */
  boundary: string;
  connection_state: ConnectionState;
  /** This user's own connections to this provider. */
  connections: IntegrationConnection[];
};

export type HealthIntegrationsResponse = { integrations: HealthIntegration[] };

export const getHealthIntegrations = () =>
  apiClient<HealthIntegrationsResponse>("/health/integrations");

/**
 * Registers a connection. Credentials are not accepted and are not part of the payload type, so the
 * client cannot offer a field the server would reject.
 */
export const connectHealthProvider = (payload: {
  provider: string;
  device_name?: string;
  device_type?: string;
  external_device_id?: string;
}) => apiClient<IntegrationConnection>("/health/devices", { method: "POST", ...json(payload) });

/** Plain label and colour for a state, so every surface words it the same way. */
export function connectionStateLabel(state: ConnectionState): { label: string; tone: string } {
  switch (state) {
    case "connected":
      return { label: "Up to date", tone: "#4ade80" };
    case "connecting":
      // Not an error: the connection exists and simply has not produced data yet.
      return { label: "Connected, waiting for first sync", tone: "#fbbf24" };
    case "syncing":
      return { label: "Syncing", tone: "#38bdf8" };
    case "sync_failed":
      return { label: "Last sync failed", tone: "#f87171" };
    default:
      return { label: "Not connected", tone: "#94a3b8" };
  }
}

/** What a provider's availability means, in words that do not promise anything. */
export function availabilityLabel(availability: ProviderAvailability): string {
  switch (availability) {
    case "native_bridge":
      return "Uses a mobile app";
    case "server_credentials_required":
      return "Needs server credentials";
    case "native_app_required":
      return "Needs a mobile app";
    case "manual":
      return "Always available";
    default:
      return "Unavailable";
  }
}
