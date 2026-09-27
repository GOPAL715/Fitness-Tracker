// @vitest-environment jsdom
/**
 * WorkoutsView row mutations and the offline replay transport.
 *
 * <p>Two regressions are pinned here. Row actions used to discard the returned error and refresh
 * regardless, so a failed delete looked like it had worked. The offline replay also used to go out
 * with no Authorization header, relying purely on the server-side idempotency ledger.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import WorkoutsView from "../src/views/WorkoutsView";
import { setAuthTokens } from "../src/lib/api/apiClient";
import { enqueue, listAll } from "../src/lib/offline/mutationQueue";
import { syncWorkoutSessions } from "../src/lib/offline/workoutSync";
import { installFakeIndexedDb } from "./fakeIndexedDb";
import type { Exercise, Workout } from "../src/lib/types";

type Call = { url: string; method: string; headers: Record<string, string> };
let calls: Call[] = [];

function stubFetch(status: number) {
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    calls.push({
      url: String(input),
      method: init?.method ?? "GET",
      headers: (init?.headers as Record<string, string>) ?? {},
    });
    return { ok: status >= 200 && status < 300, status, json: async () => ({ id: "w1" }) } as Response;
  });
}

const workout: Workout = {
  id: "w1",
  title: "Morning Run",
  workout_type: "Cardio",
  duration_minutes: 30,
  calories_burned: 300,
  intensity: "Moderate",
  perceived_effort: 6,
  distance_miles: null,
  completed: false,
  notes: null,
  created_at: "2026-01-01T00:00:00Z",
};

const exercise: Exercise = {
  id: "e1",
  name: "Bench Press",
  description: "",
  muscle_group: "Chest",
  secondary_muscles: [],
  equipment: "Barbell",
  difficulty: "Intermediate",
  instructions: "",
  is_compound: true,
};

/** Renders on the History tab, where the per-row toggle and delete controls live. */
function renderWorkouts(onRefresh = vi.fn()) {
  render(
    <WorkoutsView workouts={[workout]} plan={[]} sessions={[]} templates={[]} exercises={[exercise]} onRefresh={onRefresh} />
  );
  fireEvent.click(screen.getByRole("button", { name: /history/i }));
  return { onRefresh };
}

beforeEach(() => {
  calls = [];
  installFakeIndexedDb();
  setAuthTokens("test-access-token");
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  setAuthTokens(null, null);
});

describe("WorkoutsView row mutations", () => {
  it("toggles a workout and refreshes on success", async () => {
    stubFetch(200);
    const { onRefresh } = renderWorkouts();

    fireEvent.click(screen.getByLabelText("Toggle completion"));

    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0].method).toBe("PUT");
    expect(calls[0].url).toContain("/workouts/w1");
    await waitFor(() => expect(onRefresh).toHaveBeenCalled());
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("deletes a workout and refreshes on success", async () => {
    stubFetch(200);
    const { onRefresh } = renderWorkouts();

    fireEvent.click(screen.getByLabelText("Delete workout"));

    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0].method).toBe("DELETE");
    await waitFor(() => expect(onRefresh).toHaveBeenCalled());
  });

  it("surfaces a failed delete instead of refreshing silently", async () => {
    stubFetch(500);
    const { onRefresh } = renderWorkouts();

    fireEvent.click(screen.getByLabelText("Delete workout"));

    // Previously the error was discarded and onRefresh ran anyway, so a failure looked like success.
    await screen.findByText("That workout could not be deleted. Please try again.");
    expect(onRefresh).not.toHaveBeenCalled();
  });

  it("surfaces a failed toggle", async () => {
    stubFetch(500);
    const { onRefresh } = renderWorkouts();

    fireEvent.click(screen.getByLabelText("Toggle completion"));

    await screen.findByText("That workout could not be updated. Please try again.");
    expect(onRefresh).not.toHaveBeenCalled();
  });

  it("re-enables the row control after a failure", async () => {
    stubFetch(500);
    renderWorkouts();

    fireEvent.click(screen.getByLabelText("Toggle completion"));

    // The button must not stay disabled after the error.
    await screen.findByText("That workout could not be updated. Please try again.");
    await waitFor(() => expect((screen.getByLabelText("Toggle completion") as HTMLButtonElement).disabled).toBe(false));
  });

  it("clears the previous error when a later attempt succeeds", async () => {
    stubFetch(500);
    renderWorkouts();
    fireEvent.click(screen.getByLabelText("Toggle completion"));
    await screen.findByText("That workout could not be updated. Please try again.");

    vi.restoreAllMocks();
    stubFetch(200);
    fireEvent.click(screen.getByLabelText("Toggle completion"));

    await waitFor(() => expect(screen.queryByText("That workout could not be updated. Please try again.")).toBeNull());
  });
});

describe("offline replay authorization", () => {
  async function queueOne() {
    await enqueue({
      opId: "op-auth-1",
      resource: "/workout-sessions/complete",
      method: "POST",
      payload: { session: { title: "Queued" }, exercises: [] },
    });
  }

  it("sends the current access token and the idempotency key on replay", async () => {
    await queueOne();
    stubFetch(200);

    const summary = await syncWorkoutSessions();

    expect(summary.synced).toBe(1);
    expect(calls).toHaveLength(1);
    expect(calls[0].url).toContain("/workout-sessions/complete");
    expect(calls[0].headers.Authorization).toBe("Bearer test-access-token");
    // Replay protection must survive the auth change.
    expect(calls[0].headers["Idempotency-Key"]).toBe("op-auth-1");
    expect(await listAll()).toHaveLength(0);
  });

  it("does not retry forever when there is no access token", async () => {
    await queueOne();
    setAuthTokens(null, null);
    stubFetch(200);

    const summary = await syncWorkoutSessions();

    // 401 is not retryable, so the operation is parked rather than retried on every reconnect.
    expect(summary.synced).toBe(0);
    expect(summary.failed).toBe(1);
    expect(calls).toHaveLength(0);
    const [queued] = await listAll();
    expect(queued.state).toBe("failed");
    expect(queued.lastError).toBe("http_401");
  });

  it("keeps the session queued when the server is unreachable", async () => {
    await queueOne();
    vi.spyOn(globalThis, "fetch").mockRejectedValue(new TypeError("Failed to fetch"));

    const summary = await syncWorkoutSessions();

    expect(summary.synced).toBe(0);
    const [queued] = await listAll();
    expect(queued.state).toBe("pending");
  });

  it("never stores a token in the queue itself", async () => {
    await queueOne();
    const raw = JSON.stringify(await listAll());
    expect(raw).not.toMatch(/test-access-token|authorization|bearer/i);
  });
});
