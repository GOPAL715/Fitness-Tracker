// @vitest-environment jsdom
/**
 * Goals, through the real component and the real adapter.
 *
 * <p>Pins two regressions: a goal row mutation used to discard its error and refresh regardless, and
 * the estimated-completion projection was permanently unreachable because no goal had a start date.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import GoalsView, { computeGoalProgress } from "../src/views/GoalsView";
import { setAuthTokens } from "../src/lib/api/apiClient";
import type { Goal } from "../src/lib/types";

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

const goal: Goal = {
  id: "g1",
  user_id: "u1",
  goal_type: "Build Strength",
  title: "Bench 225",
  description: "",
  start_value: 100,
  target_value: 200,
  current_value: 150,
  unit: "lbs",
  // The server now stamps this, which is what makes the projection reachable.
  start_date: "2026-09-01",
  target_date: null,
  status: "active",
  created_at: "2026-09-01T00:00:00Z",
};

function renderGoals(goals: Goal[] = [goal], onRefresh: ReturnType<typeof vi.fn> = vi.fn()) {
  return { onRefresh, ...render(<GoalsView goals={goals} profile={null} onRefresh={onRefresh} />) };
}

beforeEach(() => { calls = []; setAuthTokens("test-access-token"); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); setAuthTokens(null, null); });

describe("goal progress calculation", () => {
  it("reports a percentage for a goal with a start date", () => {
    const progress = computeGoalProgress(goal);
    expect(progress.pct).toBe(50);
    expect(progress.direction).toBe("up");
    expect(progress.trend).toBe("Progress started");
  });

  it("projects an estimated date once a start date exists", () => {
    // This was permanently unreachable while every goal had a null start_date.
    expect(computeGoalProgress({ ...goal, current_value: 120 }).estimatedDate).not.toBeNull();
  });

  it("handles a decreasing goal", () => {
    const down = { ...goal, start_value: 200, target_value: 150, current_value: 150 };
    expect(computeGoalProgress(down).pct).toBe(100);
    expect(computeGoalProgress(down).direction).toBe("down");
  });

  it("never exceeds 100 percent", () => {
    expect(computeGoalProgress({ ...goal, current_value: 250 }).pct).toBe(100);
  });

  it("does not go negative when progress regresses", () => {
    expect(computeGoalProgress({ ...goal, current_value: 50 }).pct).toBe(0);
  });

  it("handles a zero-span goal without dividing by zero", () => {
    expect(computeGoalProgress({ ...goal, start_value: 100, target_value: 100, current_value: 100 }).pct).toBe(100);
  });
});

describe("goal creation", () => {
  it("POSTs a new goal and refreshes on success", async () => {
    stubFetch(200, { id: "g2" });
    const { onRefresh } = renderGoals([]);

    fireEvent.click(screen.getByRole("button", { name: /new goal/i }));
    fireEvent.change(screen.getByPlaceholderText(/reach 175 lbs/i), { target: { value: "Squat 300" } });
    fireEvent.click(screen.getByRole("button", { name: /save goal/i }));

    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0].method).toBe("POST");
    expect(calls[0].url).toContain("/goals");
    expect(calls[0].body.title).toBe("Squat 300");
    await waitFor(() => expect(onRefresh).toHaveBeenCalled());
  });

  it("refuses to save without a title and sends nothing", () => {
    stubFetch(200);
    renderGoals([]);
    fireEvent.click(screen.getByRole("button", { name: /new goal/i }));
    fireEvent.click(screen.getByRole("button", { name: /save goal/i }));

    expect(screen.getByText("Give the goal a title.")).toBeTruthy();
    expect(calls).toHaveLength(0);
  });

  it("surfaces a rejected save and re-enables the button", async () => {
    stubFetch(400, { status: 400, code: "invalid_request", message: "target_value must differ from start_value" });
    renderGoals([]);

    fireEvent.click(screen.getByRole("button", { name: /new goal/i }));
    fireEvent.change(screen.getByPlaceholderText(/reach 175 lbs/i), { target: { value: "Bad Goal" } });
    fireEvent.click(screen.getByRole("button", { name: /save goal/i }));

    await screen.findByText("That goal could not be saved. Please try again.");
    await waitFor(() => expect((screen.getByRole("button", { name: /save goal/i }) as HTMLButtonElement).disabled).toBe(false));
  });

  it("re-enables the button when the request throws", async () => {
    vi.spyOn(globalThis, "fetch").mockRejectedValue(new TypeError("Failed to fetch"));
    renderGoals([]);

    fireEvent.click(screen.getByRole("button", { name: /new goal/i }));
    fireEvent.change(screen.getByPlaceholderText(/reach 175 lbs/i), { target: { value: "Throwing Goal" } });
    fireEvent.click(screen.getByRole("button", { name: /save goal/i }));

    await screen.findByText("That goal could not be saved. Please try again.");
    await waitFor(() => expect((screen.getByRole("button", { name: /save goal/i }) as HTMLButtonElement).disabled).toBe(false));
  });
});

describe("goal row mutations", () => {
  it("updates progress and refreshes on success", async () => {
    stubFetch(200, goal);
    const { onRefresh } = renderGoals();

    fireEvent.click(screen.getByRole("button", { name: /mark achieved/i }));

    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0].method).toBe("PUT");
    expect(calls[0].url).toContain("/goals/g1");
    expect(calls[0].body.status).toBe("achieved");
    await waitFor(() => expect(onRefresh).toHaveBeenCalled());
  });

  it("surfaces a failed update instead of refreshing silently", async () => {
    stubFetch(500);
    const { onRefresh } = renderGoals();

    fireEvent.click(screen.getByRole("button", { name: /mark achieved/i }));

    // Previously the error was discarded and onRefresh ran anyway.
    await screen.findByText("That goal could not be updated. Please try again.");
    expect(onRefresh).not.toHaveBeenCalled();
  });

  it("surfaces a failed delete instead of closing the dialog", async () => {
    stubFetch(500);
    const { onRefresh } = renderGoals();

    fireEvent.click(screen.getByLabelText("Delete goal"));
    fireEvent.click(within(screen.getByText("Delete goal?").closest(".modal") as HTMLElement).getByRole("button", { name: "Delete goal" }));

    await screen.findByText("That goal could not be deleted. Please try again.");
    expect(onRefresh).not.toHaveBeenCalled();
  });

  it("deletes and refreshes on success", async () => {
    stubFetch(200);
    const { onRefresh } = renderGoals();

    fireEvent.click(screen.getByLabelText("Delete goal"));
    fireEvent.click(within(screen.getByText("Delete goal?").closest(".modal") as HTMLElement).getByRole("button", { name: "Delete goal" }));

    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0].method).toBe("DELETE");
    await waitFor(() => expect(onRefresh).toHaveBeenCalled());
  });

  it("re-enables the row control after a failure", async () => {
    stubFetch(500);
    renderGoals();

    fireEvent.click(screen.getByRole("button", { name: /mark achieved/i }));
    await screen.findByText("That goal could not be updated. Please try again.");
    await waitFor(() => expect((screen.getByRole("button", { name: /mark achieved/i }) as HTMLButtonElement).disabled).toBe(false));
  });
});