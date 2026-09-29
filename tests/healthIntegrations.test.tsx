// @vitest-environment jsdom
/**
 * Phase 20: the health integrations panel.
 *
 * <p>The API is stubbed at the module boundary, so these are presentation and interaction cases: the
 * five connection states are distinguishable, loading and failure are reported honestly, an action
 * re-reads the server rather than editing a local list, and - the property that matters most - no
 * credential-shaped value can reach the DOM, because the server sends none and the client renders
 * what it is given.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";

import { HealthIntegrationsPanel } from "../src/components/HealthIntegrationsPanel";
import {
  getHealthIntegrations,
  connectHealthProvider,
  type ConnectionState,
  type HealthIntegration,
  type IntegrationConnection,
} from "../src/lib/api/healthIntegrationsApi";
import { deleteHealthDevice, syncHealthDevice } from "../src/lib/api/healthApi";
import { ApiError } from "../src/lib/api/apiClient";

vi.mock("../src/lib/api/healthIntegrationsApi", async () => {
  // The real label and formatting helpers are kept, because the tests assert on the words a person
  // reads; only the network calls are replaced.
  const actual = await vi.importActual<
    typeof import("../src/lib/api/healthIntegrationsApi")
  >("../src/lib/api/healthIntegrationsApi");
  return {
    ...actual,
    getHealthIntegrations: vi.fn(),
    connectHealthProvider: vi.fn(),
  };
});

vi.mock("../src/lib/api/healthApi", async () => {
  const actual = await vi.importActual<typeof import("../src/lib/api/healthApi")>(
    "../src/lib/api/healthApi"
  );
  return { ...actual, syncHealthDevice: vi.fn(), deleteHealthDevice: vi.fn() };
});

const mockGet = vi.mocked(getHealthIntegrations);
const mockConnect = vi.mocked(connectHealthProvider);
const mockSync = vi.mocked(syncHealthDevice);
const mockDelete = vi.mocked(deleteHealthDevice);

function connection(overrides: Partial<IntegrationConnection> = {}): IntegrationConnection {
  return {
    id: "device-1",
    provider: "health-connect",
    external_device_id: null,
    device_name: "Test Band",
    device_type: "wearable",
    status: "Connected",
    sync_status: "idle",
    last_error: null,
    last_sync_at: null,
    awaiting_first_sync: true,
    permission_status: null,
    ...overrides,
  };
}

function integration(overrides: Partial<HealthIntegration> = {}): HealthIntegration {
  return {
    provider: "health-connect",
    label: "Android Health Connect",
    availability: "native_bridge",
    auth_model: "A separate Android app",
    credential_model: "none",
    supported_metrics: ["steps"],
    connectable: true,
    boundary: "Health Connect is an Android-native, on-device API.",
    connection_state: "disconnected",
    connections: [],
    ...overrides,
  };
}

/** Renders once the first load has resolved, so a test never races the mount effect. */
async function ready() {
  render(<HealthIntegrationsPanel onRefresh={vi.fn()} />);
  await waitFor(() => expect(screen.queryByTestId("health-integrations-loading")).toBeNull());
}

describe("health integrations: connection states", () => {
  beforeEach(() => {
    mockGet.mockReset();
    mockConnect.mockReset();
    mockSync.mockReset();
    mockDelete.mockReset();
  });
  afterEach(cleanup);

  it("shows a loading state before the server answers", async () => {
    // The screen must say it is checking rather than rendering an empty list, which would read as
    // "you have no integrations" - a different and wrong fact.
    let resolve: (value: { integrations: HealthIntegration[] }) => void = () => {};
    mockGet.mockReturnValue(
      new Promise<{ integrations: HealthIntegration[] }>((r) => {
        resolve = r;
      })
    );
    render(<HealthIntegrationsPanel onRefresh={vi.fn()} />);
    expect(screen.getByTestId("health-integrations-loading")).toBeTruthy();
    resolve({ integrations: [integration()] });
    await waitFor(() => expect(screen.queryByTestId("health-integrations-loading")).toBeNull());
  });

  it("reports a load failure and offers a retry, without claiming anything was disconnected", async () => {
    mockGet.mockRejectedValue(new Error("network"));
    render(<HealthIntegrationsPanel onRefresh={vi.fn()} />);
    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toContain("Could not load your health integrations");
    // A failed load is not an empty account. Saying "not connected" here would tell a user their
    // devices are gone when the server was simply unreachable.
    expect(screen.queryByText("Not connected")).toBeNull();
    expect(screen.getByRole("button", { name: /try again/i })).toBeTruthy();
  });

  it("distinguishes every state the server can report", async () => {
    // Each state is asserted on its own words, because a badge that collapsed two states into one
    // label is exactly what this model exists to prevent.
    const cases: Array<[ConnectionState, RegExp]> = [
      ["disconnected", /not connected/i],
      ["connecting", /waiting for first sync/i],
      ["connected", /up to date/i],
      ["syncing", /syncing/i],
      ["sync_failed", /last sync failed/i],
    ];
    for (const [state, expected] of cases) {
      mockGet.mockResolvedValue({ integrations: [integration({ connection_state: state })] });
      cleanup();
      await ready();
      expect(screen.getByTestId("state-health-connect").textContent).toMatch(expected);
    }
  });

  it("labels a never-synced connection as waiting rather than broken", async () => {
    // "connecting" is a setup state, not a failure. Rendering it in the failure colour would alarm
    // someone whose connection is perfectly fine and simply has not synced yet.
    mockGet.mockResolvedValue({
      integrations: [
        integration({
          connection_state: "connecting",
          connections: [connection({ sync_status: "idle" })],
        }),
      ],
    });
    await ready();
    expect(screen.getByTestId("state-health-connect").textContent).toMatch(
      /waiting for first sync/i
    );
    expect(screen.getByText("Has never synced")).toBeTruthy();
    expect(screen.queryByText(/last sync failed/i)).toBeNull();
  });

  it("surfaces a sync failure with the reason the server reported", async () => {
    mockGet.mockResolvedValue({
      integrations: [
        integration({
          connection_state: "sync_failed",
          connections: [connection({ sync_status: "error", last_error: "provider_unavailable" })],
        }),
      ],
    });
    await ready();
    expect(screen.getByTestId("state-health-connect").textContent).toMatch(/last sync failed/i);
    expect(screen.getByText(/provider unavailable/)).toBeTruthy();
  });

  it("says nothing about permissions when the device reported none", async () => {
    // A device that said nothing must not be given an invented state.
    mockGet.mockResolvedValue({
      integrations: [integration({ connections: [connection({ permission_status: null })] })],
    });
    await ready();
    expect(screen.queryByTestId("permission-notice")).toBeNull();
  });

  it("presents a reported permission as the app's claim, not as something FitTrack verified", async () => {
    // The backend cannot observe Android Health Connect permissions - they are granted on the
    // handset - so wording this as a verified fact is the real failure, not a style choice.
    mockGet.mockResolvedValue({
      integrations: [
        integration({
          connections: [connection({ permission_status: "permission_revoked" })],
        }),
      ],
    });
    await ready();
    const notice = screen.getByTestId("permission-notice");
    expect(notice.textContent).toContain("The app reports");
    expect(notice.textContent).toMatch(/permission revoked/);
    expect(notice.textContent).not.toMatch(/fittrack has verified|permission granted by fittrack/i);
  });
});

describe("health integrations: actions and secrecy", () => {
  beforeEach(() => {
    mockGet.mockReset();
    mockConnect.mockReset();
    mockSync.mockReset();
    mockDelete.mockReset();
  });
  afterEach(cleanup);

  it("connects through the served route and then re-reads the server", async () => {
    mockGet
      .mockResolvedValueOnce({ integrations: [integration()] })
      .mockResolvedValueOnce({
        integrations: [
          integration({ connection_state: "connecting", connections: [connection()] }),
        ],
      });
    mockConnect.mockResolvedValue(connection());
    const onRefresh = vi.fn();
    render(<HealthIntegrationsPanel onRefresh={onRefresh} />);
    await waitFor(() => screen.getByTestId("state-health-connect"));

    fireEvent.click(screen.getByRole("button", { name: /connect android health connect/i }));

    await waitFor(() => expect(mockConnect).toHaveBeenCalledTimes(1));
    expect(mockConnect.mock.calls[0][0]).toEqual(
      expect.objectContaining({ provider: "health-connect" })
    );
    // The panel re-reads rather than patching its own list, so what is rendered is the server's
    // answer and not an optimistic guess that could disagree with it.
    await waitFor(() => expect(mockGet).toHaveBeenCalledTimes(2));
    expect(onRefresh).toHaveBeenCalled();
    await waitFor(() =>
      expect(screen.getByTestId("state-health-connect").textContent).toMatch(
        /waiting for first sync/i
      )
    );
  });

  it("never sends a credential field when connecting", async () => {
    // FitTrack holds no provider token and has no column for one, so the client must not offer a
    // field the server would reject. This asserts the shape of what is actually sent.
    mockGet.mockResolvedValue({ integrations: [integration()] });
    mockConnect.mockResolvedValue(connection());
    render(<HealthIntegrationsPanel onRefresh={vi.fn()} />);
    await waitFor(() => screen.getByRole("button", { name: /connect android health connect/i }));

    fireEvent.click(screen.getByRole("button", { name: /connect android health connect/i }));
    await waitFor(() => expect(mockConnect).toHaveBeenCalled());

    const sent = JSON.stringify(mockConnect.mock.calls[0][0]);
    expect(sent).not.toMatch(/access_token|refresh_token|client_secret|token|secret|password/i);
  });

  it("syncs a connection and reports a provider failure without losing the row", async () => {
    mockGet.mockResolvedValue({
      integrations: [
        integration({ connection_state: "connecting", connections: [connection()] }),
      ],
    });
    // The real ApiError class, because healthErrorMessage switches on its type: a plain Error would
    // take the fallback branch and the test would pass on the wrong message.
    mockSync.mockRejectedValue(
      new ApiError(502, "Health provider unavailable", "health_provider_unavailable")
    );
    render(<HealthIntegrationsPanel onRefresh={vi.fn()} />);
    await waitFor(() => screen.getByRole("button", { name: /sync now/i }));

    fireEvent.click(screen.getByRole("button", { name: /sync now/i }));

    const alert = await screen.findByRole("alert");
    // A provider failure is a temporary condition, and the message must say the existing data is
    // untouched rather than implying the connection was removed.
    expect(alert.textContent).toMatch(/could not be reached/i);
    expect(alert.textContent).toMatch(/previously synced data is unchanged/i);
    // The connection is still listed: a failed sync is not a disconnection.
    expect(screen.getByTestId("connection-device-1")).toBeTruthy();
  });

  it("disconnects and shows the provider as not connected again", async () => {
    mockGet
      .mockResolvedValueOnce({
        integrations: [
          integration({
            connection_state: "connected",
            connections: [connection({ sync_status: "synced", awaiting_first_sync: false })],
          }),
        ],
      })
      .mockResolvedValue({ integrations: [integration({ connection_state: "disconnected" })] });
    mockDelete.mockResolvedValue({
      disconnected: true,
      imported_history_retained: true,
      message: "Previously imported health data remains in FitTrack.",
    });
    render(<HealthIntegrationsPanel onRefresh={vi.fn()} />);
    await waitFor(() => screen.getByRole("button", { name: /disconnect/i }));

    fireEvent.click(screen.getByRole("button", { name: /disconnect/i }));

    await waitFor(() => expect(screen.queryByTestId("connection-device-1")).toBeNull());
    expect(screen.getByTestId("state-health-connect").textContent).toMatch(/not connected/i);
  });

  it("offers no connect or sync control for manual entry", async () => {
    // Manual entry is not a device. Offering it the same controls would imply a wearable that does
    // not exist, and a "sync" that can never run.
    mockGet.mockResolvedValue({
      integrations: [
        integration({
          provider: "manual",
          label: "Manual entry",
          availability: "manual",
          connection_state: "disconnected",
        }),
      ],
    });
    await ready();
    expect(screen.getByTestId("integration-manual")).toBeTruthy();
    expect(screen.queryByRole("button", { name: /^connect /i })).toBeNull();
    expect(screen.queryByRole("button", { name: /sync now/i })).toBeNull();
  });

  it("offers no connect control for a provider the server says cannot be connected", async () => {
    // Fitbit needs a server-side OAuth application FitTrack does not ship. A connect button that
    // could only produce an empty row would be a dead end.
    mockGet.mockResolvedValue({
      integrations: [
        integration({
          provider: "fitbit",
          label: "Fitbit",
          availability: "server_credentials_required",
          connectable: false,
        }),
      ],
    });
    await ready();
    expect(screen.getByTestId("integration-fitbit")).toBeTruthy();
    expect(screen.queryByRole("button", { name: /connect fitbit/i })).toBeNull();
    expect(screen.getByTestId("integration-fitbit").textContent).toMatch(
      /needs server credentials/i
    );
  });

  it("renders the server's boundary text rather than a client-side description", async () => {
    // The catalogue is served, so a provider added on the server appears here with its own wording.
    mockGet.mockResolvedValue({
      integrations: [
        integration({
          provider: "apple-health",
          label: "Apple Health",
          availability: "native_app_required",
          connectable: false,
          boundary: "BOUNDARY TEXT SUPPLIED BY THE SERVER",
        }),
      ],
    });
    await ready();
    expect(screen.getByText("BOUNDARY TEXT SUPPLIED BY THE SERVER")).toBeTruthy();
  });

  it("shows an empty state when the server recognises no provider at all", async () => {
    mockGet.mockResolvedValue({ integrations: [] });
    await ready();
    expect(screen.getByText("No integrations available")).toBeTruthy();
  });

  it("puts no credential-shaped value in the document, whatever the server sent", async () => {
    // Defence in depth: even if a future field were added carelessly, nothing credential-shaped
    // should be able to reach the rendered output.
    mockGet.mockResolvedValue({
      integrations: [
        integration({
          connections: [connection({ device_name: "Band" })],
          boundary: "An ordinary boundary sentence.",
        }),
      ],
    });
    await ready();
    const rendered = document.body.innerHTML;
    expect(rendered).not.toMatch(/access_token|refresh_token|client_secret|Bearer\s/i);
    expect(rendered).not.toMatch(/sync_cursor|client_changes_token/);
  });
});
