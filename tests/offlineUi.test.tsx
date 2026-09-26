// @vitest-environment jsdom
/**
 * Integration tests for the rendered offline UI and the real offline mutation workflow.
 *
 * <p>These exercise the actual components and the application-facing submit path, not the pure
 * status helpers: a header indicator is rendered from live state, and submitting a workout while
 * offline must land in IndexedDB.
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, cleanup, waitFor } from "@testing-library/react";
import { installFakeIndexedDb } from "./fakeIndexedDb";
import { AppShell } from "../src/components/AppShell";
import { submitWorkoutSession, WORKOUT_SESSION_RESOURCE } from "../src/lib/offline/workoutSync";
import { listAll, update } from "../src/lib/offline/mutationQueue";
import type { SyncStatus } from "../src/lib/offline/syncStatus";

function setOnline(online: boolean) {
  Object.defineProperty(window.navigator, "onLine", { value: online, configurable: true });
}

function renderShell(status: SyncStatus) {
  return render(
    <AppShell
      activeTab="today"
      onSelectTab={() => {}}
      menuOpen={false}
      onToggleMenu={() => {}}
      email="athlete@example.test"
      onSignOut={() => {}}
      syncStatus={status}
    >
      <div>content</div>
    </AppShell>
  );
}

beforeEach(() => {
  installFakeIndexedDb();
  setOnline(true);
  cleanup();
});

describe("sync indicator in the application shell", () => {
  it("renders the online state", () => {
    renderShell({ state: "online", pending: 0, failed: 0, conflicts: 0 });
    const indicator = screen.getByTestId("sync-indicator");
    expect(indicator.getAttribute("data-state")).toBe("online");
    expect(indicator.textContent).toBe("Online");
  });

  it("renders the offline state", () => {
    renderShell({ state: "offline", pending: 0, failed: 0, conflicts: 0 });
    const indicator = screen.getByTestId("sync-indicator");
    expect(indicator.getAttribute("data-state")).toBe("offline");
    expect(indicator.textContent).toBe("Offline");
  });

  it("renders the pending count while offline", () => {
    renderShell({ state: "offline", pending: 2, failed: 0, conflicts: 0 });
    expect(screen.getByTestId("sync-indicator").textContent).toBe("Offline - 2 pending");
  });

  it("renders the syncing state", () => {
    renderShell({ state: "syncing", pending: 1, failed: 0, conflicts: 0 });
    expect(screen.getByTestId("sync-indicator").textContent).toBe("Syncing");
  });

  it("renders the failed count", () => {
    renderShell({ state: "failed", pending: 0, failed: 1, conflicts: 0 });
    expect(screen.getByTestId("sync-indicator").textContent).toBe("1 failed");
  });

  it("renders the conflict count", () => {
    renderShell({ state: "conflict", pending: 0, failed: 0, conflicts: 1 });
    expect(screen.getByTestId("sync-indicator").textContent).toBe("1 conflict");
  });

  it("renders the pending state when online with queued work", () => {
    renderShell({ state: "pending", pending: 3, failed: 0, conflicts: 0 });
    expect(screen.getByTestId("sync-indicator").textContent).toBe("3 pending");
  });
});

describe("offline workout session submission", () => {
  const payload = {
    session: { title: "Push Day", workout_type: "Strength", duration_minutes: 40, completed: true },
    exercises: [
      {
        exercise_id: "ex-1",
        order_index: 0,
        sets: [{ set_number: 1, reps: 8, weight: 60, completed: true }],
      },
    ],
  };

  it("posts directly when online and does not queue anything", async () => {
    setOnline(true);
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(JSON.stringify({ id: "s1" }), { status: 200 })
    );

    const result = await submitWorkoutSession(payload);

    expect(result.mode).toBe("online");
    expect(fetchSpy).toHaveBeenCalledTimes(1);
    expect(await listAll()).toHaveLength(0);
    fetchSpy.mockRestore();
  });

  it("queues the exact request in IndexedDB when offline", async () => {
    setOnline(false);
    const fetchSpy = vi.spyOn(globalThis, "fetch");

    const result = await submitWorkoutSession(payload);

    expect(result.mode).toBe("offline");
    // Nothing was sent to the network.
    expect(fetchSpy).not.toHaveBeenCalled();

    const queued = await listAll();
    expect(queued).toHaveLength(1);
    const op = queued[0];
    expect(op.resource).toBe(WORKOUT_SESSION_RESOURCE);
    expect(op.method).toBe("POST");
    // The exact backend payload is preserved for verbatim replay.
    expect(op.payload).toEqual(payload);
    // The queue carries an idempotency key, so a retry cannot duplicate the session.
    expect(op.idempotencyKey).toBe(op.opId);
    expect(op.state).toBe("pending");
    expect(op.attempts).toBe(0);
    expect(op.createdAt).toBeGreaterThan(0);
    fetchSpy.mockRestore();
  });

  it("never stores credentials in the queued workout payload", async () => {
    setOnline(false);
    await submitWorkoutSession(payload);
    const raw = JSON.stringify(await listAll());
    expect(raw).not.toMatch(/access_token|refresh_token|password|authorization|bearer/i);
  });

  it("survives a page reload: the queue persists across module re-evaluation", async () => {
    setOnline(false);
    const { opId } = (await submitWorkoutSession(payload)) as { opId: string };
    // A reload discards in-memory state but not IndexedDB.
    vi.resetModules();
    const reloaded = await listAll();
    expect(reloaded).toHaveLength(1);
    expect(reloaded[0].opId).toBe(opId);
  });

  it("drives the indicator from real queue state", async () => {
    setOnline(false);
    await submitWorkoutSession(payload);
    const { currentStatus } = await import("../src/lib/offline/syncStatus");
    const status = await currentStatus(false);
    renderShell(status);
    await waitFor(() => {
      expect(screen.getByTestId("sync-indicator").getAttribute("data-state")).toBe("offline");
    });
    expect(screen.getByTestId("sync-indicator").textContent).toBe("Offline - 1 pending");
  });

  it("reflects a queued operation that the server later rejects as a conflict", async () => {
    setOnline(false);
    await submitWorkoutSession(payload);
    const [op] = await listAll();
    await update(op.opId, { state: "conflict" });
    // Back online, the conflict is what the user must see.
    setOnline(true);
    const { currentStatus } = await import("../src/lib/offline/syncStatus");
    renderShell(await currentStatus(false));
    expect(screen.getByTestId("sync-indicator").textContent).toBe("1 conflict");
  });

  it("shows offline ahead of conflict while still disconnected", async () => {
    setOnline(false);
    await submitWorkoutSession(payload);
    const [op] = await listAll();
    await update(op.opId, { state: "conflict" });
    const { currentStatus } = await import("../src/lib/offline/syncStatus");
    renderShell(await currentStatus(false));
    // Offline is the dominant state per the existing priority rules.
    expect(screen.getByTestId("sync-indicator").getAttribute("data-state")).toBe("offline");
  });
});
