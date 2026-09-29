import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import {
  listHealthDevices, addHealthDevice, syncHealthDevice, deleteHealthDevice,
  healthErrorMessage, syncStateLabel, DISCONNECT_RETENTION_NOTICE,
  permissionStateNotice, permissionStateLabel, isClientReportedPermission,
} from "../src/lib/api/healthApi";
import { HEALTH_PROVIDERS } from "../src/lib/healthProviders";
import { ALLOWED_WINDOW_DAYS, DEFAULT_WINDOW_DAYS } from "../src/lib/api/coachApi";
import { ApiError, setAuthTokens } from "../src/lib/api/apiClient";

/**
 * The health API client, against a stubbed transport.
 *
 * The bug this pins is concrete: the client called /health-devices, which the backend has never
 * served, so every health request was a 404. The path assertions below fail if that ever drifts
 * again, and the state and messaging tests pin the states the product depends on.
 */

type Call = { url: string; method: string; body: any };
let calls: Call[] = [];

function stubFetch(status: number, body: unknown = {}) {
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    calls.push({
      url: String(input),
      method: init?.method ?? "GET",
      body: init?.body ? JSON.parse(String(init.body)) : undefined,
    });
    return { ok: status >= 200 && status < 300, status, json: async () => body } as Response;
  });
}

const device = {
  id: "11111111-1111-1111-1111-111111111111",
  provider: "health-connect",
  external_device_id: "ANDROID-1",
  device_name: "Pixel Watch",
  device_type: "wearable",
  status: "Connected",
  sync_status: "synced",
  last_error: null,
  last_sync_at: "2026-09-20T10:00:00Z",
  awaiting_first_sync: false,
};

beforeEach(() => {
  calls = [];
  setAuthTokens("test-access-token");
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("health API routes", () => {
  it("lists devices from the canonical /health/devices route, not the old 404 path", async () => {
    stubFetch(200, [device]);
    await listHealthDevices();

    expect(calls[0].url).toContain("/health/devices");
    expect(calls[0].url).not.toContain("/health-devices");
  });

  it("registers a device on the same canonical route", async () => {
    stubFetch(200, device);
    await addHealthDevice({ device_name: "Pixel Watch", provider: "health-connect" });

    expect(calls[0].url).toContain("/health/devices");
    expect(calls[0].method).toBe("POST");
  });

  it("syncs through the device sync route", async () => {
    stubFetch(200, {
      status: "synced", activity_records: 2, body_records: 1,
      pages_fetched: 1, records_rejected: 0, truncated: false,
    });
    const result = await syncHealthDevice("dev-1");

    expect(calls[0].url).toContain("/health/devices/dev-1/sync");
    expect(result.pages_fetched).toBe(1);
  });

  it("disconnects through the device route and reports retention", async () => {
    stubFetch(200, {
      disconnected: true, imported_history_retained: true,
      message: "Disconnecting stops future syncing. Previously imported health data remains in FitTrack.",
    });
    const result = await deleteHealthDevice("dev-1");

    expect(calls[0].url).toContain("/health/devices/dev-1");
    expect(calls[0].method).toBe("DELETE");
    expect(result.imported_history_retained).toBe(true);
  });

  it("exposes no method that would 404: every call targets a served route", async () => {
    stubFetch(200, []);
    await listHealthDevices();
    // A hyphenated path is the specific mistake being guarded against.
    expect(calls.every((c) => !c.url.includes("/health-devices"))).toBe(true);
  });
});

describe("sync states", () => {
  it("distinguishes a syncing device", () => {
    expect(syncStateLabel({ ...device, sync_status: "syncing" }).label).toBe("Syncing");
  });

  it("distinguishes a failed sync", () => {
    expect(syncStateLabel({ ...device, sync_status: "error" }).label).toBe("Last sync failed");
  });

  it("distinguishes a completed sync", () => {
    expect(syncStateLabel({ ...device, sync_status: "synced" }).label).toBe("Up to date");
  });

  it("distinguishes a connected device that has never synced", () => {
    const state = syncStateLabel({ ...device, sync_status: "idle", last_sync_at: null, awaiting_first_sync: true });
    expect(state.label).toBe("Connected, not synced yet");
  });

  it("gives a connected-but-unsynced device a distinct tone from a failed one", () => {
    const waiting = syncStateLabel({ ...device, sync_status: "idle", awaiting_first_sync: true });
    const failed = syncStateLabel({ ...device, sync_status: "error" });
    expect(waiting.tone).not.toBe(failed.tone);
  });
});

describe("error messages", () => {
  it("explains a quota refusal without claiming data was lost", () => {
    const message = healthErrorMessage(new ApiError(429, "Too Many Requests", "ai_quota_exceeded"));
    expect(message).toMatch(/limit/i);
    expect(message).not.toMatch(/deleted/i);
  });

  it("explains a replay", () => {
    expect(healthErrorMessage(new ApiError(409, "Conflict", "coach_request_already_processed"))).toMatch(/already processed/i);
  });

  it("treats a missing connection on disconnect as already gone, not an error", () => {
    expect(healthErrorMessage(new ApiError(404, "Not Found", "not_found"), "disconnect")).toMatch(/no longer exists/i);
  });

  it("reports provider unavailability and reassures about existing data", () => {
    const message = healthErrorMessage(new ApiError(502, "Bad Gateway", "ai_provider_unavailable"), "sync");
    expect(message).toMatch(/could not be reached|unavailable/i);
    expect(message).toMatch(/unchanged/i);
  });

  it("asks the user to sign in again on 401", () => {
    expect(healthErrorMessage(new ApiError(401, "Unauthorized", "unauthorized"))).toMatch(/sign in/i);
  });
});

describe("disconnect retention", () => {
  it("states that history is kept, so the UI cannot imply deletion", () => {
    expect(DISCONNECT_RETENTION_NOTICE).toMatch(/stops future syncing/i);
    expect(DISCONNECT_RETENTION_NOTICE).toMatch(/remains in FitTrack/i);
    expect(DISCONNECT_RETENTION_NOTICE).not.toMatch(/deleted|will be removed/i);
  });
});

describe("health constants", () => {
  it("keeps the three supported windows", () => {
    expect([...ALLOWED_WINDOW_DAYS]).toEqual([7, 30, 90]);
    expect(DEFAULT_WINDOW_DAYS).toBe(7);
  });
});

describe("Phase 11 Health Connect permission states", () => {
  /**
   * The server cannot verify Health Connect permissions - they are granted on the handset and only
   * the native app can observe them. So permission_required and permission_revoked are claims the
   * device makes about itself. These tests pin the distinction the UI depends on: they must be
   * described as reported, and they must never be presented as a server-verified fact.
   */

  it("marks exactly the two permission states as client-reported", () => {
    expect(isClientReportedPermission("permission_required")).toBe(true);
    expect(isClientReportedPermission("permission_revoked")).toBe(true);
    // Server-observable states must not be conflated with client claims.
    expect(isClientReportedPermission("connected")).toBe(false);
    expect(isClientReportedPermission("syncing")).toBe(false);
    expect(isClientReportedPermission("sync_failed")).toBe(false);
    expect(isClientReportedPermission("disconnected")).toBe(false);
    expect(isClientReportedPermission(null)).toBe(false);
  });

  it("phrases every permission message as something the app reports", () => {
    for (const state of ["permission_required", "permission_revoked", "connected"]) {
      const message = permissionStateNotice(state);
      expect(message).toBeTruthy();
      expect(message!.toLowerCase()).toContain("the android app reports");
    }
  });

  it("never presents a client claim as something FitTrack verified", () => {
    // A message implying verification would be the actual failure, so assert it is absent.
    for (const state of ["permission_required", "permission_revoked"]) {
      const message = permissionStateNotice(state)!.toLowerCase();
      expect(message).not.toContain("we verified");
      expect(message).not.toContain("fittrack has verified");
      expect(message).not.toContain("permission has been confirmed");
    }
  });

  it("tells the user their existing data survives a revoked permission", () => {
    expect(permissionStateNotice("permission_revoked")).toContain("unchanged");
  });

  it("shows nothing at all when a device reports no permission state", () => {
    // A web-reachable provider has no Health Connect permissions, so inventing a state would lie.
    expect(permissionStateNotice(null)).toBeNull();
    expect(permissionStateNotice(undefined)).toBeNull();
    expect(permissionStateNotice("something-else")).toBeNull();
    expect(permissionStateLabel(null)).toBeNull();
    expect(permissionStateLabel("something-else")).toBeNull();
  });

  it("labels each reportable state distinctly", () => {
    expect(permissionStateLabel("permission_required")).toBe("Permission needed");
    expect(permissionStateLabel("permission_revoked")).toBe("Permission revoked");
    expect(permissionStateLabel("connected")).toBe("Access granted");
  });

  it("keeps the native-only Health Connect boundary honest in the provider catalogue", () => {
    const healthConnect = HEALTH_PROVIDERS.find((p) => p.id === "health-connect");
    expect(healthConnect).toBeDefined();
    expect(healthConnect!.status).toBe("requires-native-app");
    expect(healthConnect!.canSyncNow).toBe(false);
    expect(healthConnect!.boundary.toLowerCase()).toContain("android");
  });
});
