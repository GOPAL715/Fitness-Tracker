import { useCallback, useEffect, useState } from "react";
import { AlertTriangle, Plug, RefreshCw, ShieldCheck, Watch } from "lucide-react";

import { EmptyState, SectionHeader } from "./ui";
import { relativeTime } from "../lib/utils";
import { deleteHealthDevice, healthErrorMessage, syncHealthDevice } from "../lib/api/healthApi";
import {
  connectHealthProvider,
  getHealthIntegrations,
  availabilityLabel,
  connectionStateLabel,
  type ConnectionState,
  type HealthIntegration,
  type IntegrationConnection,
} from "../lib/api/healthIntegrationsApi";

/**
 * The health integrations surface (Phase 20).
 *
 * <p>Everything rendered here comes from one server response: which providers this build knows, what
 * each would actually require, and what this user has connected. Nothing is hardcoded, so the list a
 * person sees cannot drift from the list the backend will accept.
 *
 * <p>Deliberately not a health dashboard. This is the integration surface only: the five connection
 * states and the three actions that already exist on the server. Reading imported data is a
 * different screen.
 *
 * <p>No credential can appear here. The response carries `credential_model` - the string "none" -
 * and nothing else about any secret, because the server holds none and sends none.
 */

type Status =
  | { kind: "loading" }
  | { kind: "ready"; integrations: HealthIntegration[]; error: string | null }
  | { kind: "error"; message: string };

/** Which provider an action is running for, so only that row shows progress. */
type Busy = { provider: string; action: "connect" | "sync" | "disconnect"; id?: string } | null;

/** The state of one connection, derived from the same stored value the server used. */
function stateOf(connection: IntegrationConnection): ConnectionState {
  switch (connection.sync_status) {
    case "synced":
      return "connected";
    case "syncing":
      return "syncing";
    case "error":
      return "sync_failed";
    // idle means the row exists and has never completed a pass, which is the connecting state.
    default:
      return "connecting";
  }
}

export function HealthIntegrationsPanel({ onRefresh }: { onRefresh?: () => void }) {
  const [status, setStatus] = useState<Status>({ kind: "loading" });
  const [busy, setBusy] = useState<Busy>(null);

  const load = useCallback(async () => {
    try {
      const response = await getHealthIntegrations();
      setStatus({ kind: "ready", integrations: response.integrations ?? [], error: null });
    } catch {
      setStatus({
        kind: "error",
        message: "Could not load your health integrations. Nothing has been changed.",
      });
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  /** Runs one action, then re-reads so what is shown is the server's state, never a local guess. */
  const run = useCallback(
    async (provider: string, action: "connect" | "sync" | "disconnect", work: () => Promise<unknown>) => {
      setBusy({ provider, action });
      try {
        await work();
        await load();
        // The rest of the screen still holds the old device list until the app reloads.
        onRefresh?.();
      } catch (error) {
        const message = healthErrorMessage(error, action === "disconnect" ? "disconnect" : "sync");
        setStatus((current) => (current.kind === "ready" ? { ...current, error: message } : current));
      } finally {
        setBusy(null);
      }
    },
    [load, onRefresh]
  );

  if (status.kind === "loading") {
    return (
      <div className="card" data-testid="health-integrations-loading">
        <SectionHeader title="Health integrations" subtitle="Checking what is connected…" />
        <div className="spinner" />
      </div>
    );
  }

  if (status.kind === "error") {
    return (
      <div className="card">
        <SectionHeader title="Health integrations" />
        <div className="form-error" role="alert" style={{ marginBottom: 16 }}>
          <AlertTriangle size={16} />
          <span>{status.message}</span>
        </div>
        <button className="btn btn-secondary btn-sm" onClick={() => void load()}>
          <RefreshCw size={14} /> Try again
        </button>
      </div>
    );
  }

  return (
    <div className="card">
      <SectionHeader
        title="Health integrations"
        subtitle="Where each health platform stands, and what you have connected."
      />
      {status.error && (
        <div className="form-error" role="alert" style={{ marginBottom: 16 }}>
          <AlertTriangle size={16} />
          <span>{status.error}</span>
        </div>
      )}
      <div className="flex-col" style={{ gap: 10 }}>
        {status.integrations.length === 0 ? (
          <EmptyState
            icon={<Watch size={28} color="#64748b" />}
            title="No integrations available"
            message="This build does not recognise any health provider."
          />
        ) : (
          status.integrations.map((integration) => (
            <IntegrationRow
              key={integration.provider}
              integration={integration}
              busy={busy && busy.provider === integration.provider ? busy : null}
              onConnect={() =>
                void run(integration.provider, "connect", () =>
                  connectHealthProvider({
                    provider: integration.provider,
                    device_name: integration.label,
                  }))
              }
              onSync={(connection) =>
                void run(integration.provider, "sync", () => syncHealthDevice(connection.id))
              }
              onDisconnect={(connection) =>
                void run(integration.provider, "disconnect", () => deleteHealthDevice(connection.id))
              }
            />
          ))
        )}
        <p className="stat-meta" style={{ marginTop: 12 }}>
          Imported data is labelled with the provider it came from. Anything you type in stays marked
          as manual entry.
        </p>
      </div>
    </div>
  );

type RowProps = {
  integration: HealthIntegration;
  busy: Busy;
  onConnect: () => void;
  onSync: (connection: IntegrationConnection) => void;
  onDisconnect: (connection: IntegrationConnection) => void;
};

function IntegrationRow({ integration, busy, onConnect, onSync, onDisconnect }: RowProps) {
  const state = connectionStateLabel(integration.connection_state);
  const connected = integration.connections.length > 0;
  // Manual entry is not a device. It is always available and there is nothing to connect or sync, so
  // offering it the same controls would imply a device that does not exist.
  const isDeviceSource = integration.provider !== "manual";

  return (
    <div className="workout-item" data-testid={`integration-${integration.provider}`}>
      <div
        className="workout-icon"
        style={{ background: `${state.tone}22`, color: state.tone }}
        aria-hidden="true"
      >
        {connected ? <Watch size={20} /> : <Plug size={20} />}
      </div>
      <div className="workout-info">
        <div className="workout-title">{integration.label}</div>
        <div className="workout-meta">
          <span className="badge" data-testid={`state-${integration.provider}`}
            style={{ background: `${state.tone}22`, color: state.tone }}>
            {state.label}
          </span>
          <span>{availabilityLabel(integration.availability)}</span>
        </div>
        {/* The boundary is the server's own statement of what this provider would require, so the
            copy cannot go stale the way a client-side description could. */}
        <p className="provider-boundary">{integration.boundary}</p>
        {integration.connections.map((connection) => (
          <ConnectionRow
            key={connection.id}
            connection={connection}
            busy={busy}
            canSync={isDeviceSource}
            onSync={() => onSync(connection)}
            onDisconnect={() => onDisconnect(connection)}
          />
        ))}
        {integration.connectable && isDeviceSource && (
          <div style={{ marginTop: 8 }}>
            <button
              className="btn btn-secondary btn-sm"
              onClick={onConnect}
              disabled={busy !== null}
            >
              <Plug size={14} />{" "}
              {busy?.action === "connect" ? "Connecting…" : `Connect ${integration.label}`}
            </button>
          </div>
        )}
      </div>
    </div>
  );
}

function ConnectionRow({ connection, busy, canSync, onSync, onDisconnect }: {
  connection: IntegrationConnection;
  busy: Busy;
  canSync: boolean;
  onSync: () => void;
  onDisconnect: () => void;
}) {
  const state = connectionStateLabel(stateOf(connection));
  return (
    <div
      style={{ marginTop: 8, paddingLeft: 12, borderLeft: "2px solid #1e293b" }}
      data-testid={`connection-${connection.id}`}
    >
      <div className="workout-meta">
        <span>{connection.device_name ?? "Connected device"}</span>
        <span className="badge" style={{ background: `${state.tone}22`, color: state.tone }}>
          {state.label}
        </span>
      </div>
      <p className="stat-meta">
        {connection.last_sync_at
          ? `Last sync ${relativeTime(connection.last_sync_at)}`
          : "Has never synced"}
      </p>
      {connection.last_error && (
        <p className="stat-meta" role="status">
          <AlertTriangle size={13} /> Last sync could not complete (
          {connection.last_error.replace(/_/g, " ")}).
        </p>
      )}
      {connection.permission_status && (
        <p className="stat-meta" role="status" data-testid="permission-notice">
          {/* Phrased as what the device reported, because the server cannot verify these permissions
              itself: they are granted on the handset and are not observable from here. Presenting the
              claim as a verified fact is the actual failure this wording avoids. */}
          <ShieldCheck size={13} /> The app reports:{" "}
          {connection.permission_status.replace(/_/g, " ")}.
        </p>
      )}
      <div style={{ display: "flex", gap: 8, marginTop: 8 }}>
        {canSync && (
          <button className="btn btn-secondary btn-sm" onClick={onSync} disabled={busy !== null}>
            <RefreshCw size={14} /> {busy?.action === "sync" ? "Syncing" : "Sync now"}
          </button>
        )}
        <button
          className="btn btn-secondary btn-sm"
          onClick={onDisconnect}
          disabled={busy !== null}
          title="Disconnecting stops future syncing. Previously imported health data remains in FitTrack."
        >
          Disconnect
        </button>
      </div>
    </div>
  );
}

}
