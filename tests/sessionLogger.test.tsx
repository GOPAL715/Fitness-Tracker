// @vitest-environment jsdom
/**
 * Session logging, through the real component and the real submit path.
 *
 * <p>A session is only ever sent as one composite request, so these assert the exact payload the
 * server receives, that a rejection is reported, and that the save button never stays disabled.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import SessionLogger from "../src/views/SessionLogger";
import { setAuthTokens } from "../src/lib/api/apiClient";
import { listAll } from "../src/lib/offline/mutationQueue";
import { installFakeIndexedDb } from "./fakeIndexedDb";
import type { Exercise } from "../src/lib/types";

const exercise: Exercise = {
  id: "e1",
  name: "Bench Press",
  description: "A press",
  muscle_group: "Chest",
  secondary_muscles: [],
  equipment: "Barbell",
  difficulty: "Intermediate",
  instructions: "Brace",
  is_compound: true,
};

type Call = { url: string; method: string; body: any };
let calls: Call[] = [];

function stubFetch(status: number, body: unknown) {
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    calls.push({
      url: String(input),
      method: init?.method ?? "GET",
      body: init?.body ? JSON.parse(String(init.body)) : undefined,
    });
    return { ok: status >= 200 && status < 300, status, json: async () => body } as Response;
  });
}

function renderLogger(overrides: Record<string, unknown> = {}) {
  return render(
    <SessionLogger exercises={[exercise]} onClose={vi.fn()} onSaved={vi.fn()} onQueued={vi.fn()} {...overrides} />
  );
}

const saveButton = () => screen.getByRole("button", { name: /save session/i }) as HTMLButtonElement;

/** Puts a usable session on screen: one exercise, one set with reps. */
function fillSession() {
  fireEvent.click(screen.getByRole("button", { name: /add exercise/i }));
  fireEvent.click(screen.getByText("Bench Press"));
  fireEvent.change(screen.getByLabelText("Reps"), { target: { value: "8" } });
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
  Object.defineProperty(window.navigator, "onLine", { value: true, configurable: true });
});

describe("SessionLogger composition", () => {
  it("adds an exercise to the draft", () => {
    renderLogger();
    fireEvent.click(screen.getByRole("button", { name: /add exercise/i }));
    fireEvent.click(screen.getByText("Bench Press"));
    expect(screen.getByText("Add set")).toBeTruthy();
  });

  it("adds a set", () => {
    renderLogger();
    fireEvent.click(screen.getByRole("button", { name: /add exercise/i }));
    fireEvent.click(screen.getByText("Bench Press"));
    expect(screen.getAllByLabelText("Remove set")).toHaveLength(1);

    fireEvent.click(screen.getByRole("button", { name: /add set/i }));
    expect(screen.getAllByLabelText("Remove set")).toHaveLength(2);
  });

  it("edits a set value and reflects it in the totals", () => {
    renderLogger();
    fillSession();
    fireEvent.change(screen.getByLabelText("Weight"), { target: { value: "135" } });
    // 8 reps x 135 lb
    expect(screen.getByText("1080 lb")).toBeTruthy();
  });
});

describe("SessionLogger validation", () => {
  it("refuses to save without a name", () => {
    stubFetch(200, {});
    // addExercise seeds a default title, so it is cleared to reach the empty-name path.
    renderLogger({ initialTitle: "Placeholder" });
    fireEvent.click(screen.getByRole("button", { name: /add exercise/i }));
    fireEvent.click(screen.getByText("Bench Press"));
    fireEvent.change(screen.getByLabelText("Reps"), { target: { value: "8" } });
    fireEvent.change(screen.getByPlaceholderText("e.g. Push Day"), { target: { value: "  " } });
    fireEvent.click(saveButton());
    expect(screen.getByText("Give the session a name.")).toBeTruthy();
    expect(calls).toHaveLength(0);
  });

  it("refuses to save with no usable set", () => {
    stubFetch(200, {});
    renderLogger();
    fireEvent.click(screen.getByRole("button", { name: /add exercise/i }));
    fireEvent.click(screen.getByText("Bench Press"));
    fireEvent.change(screen.getByLabelText("Reps"), { target: { value: "0" } });
    fireEvent.change(screen.getByLabelText("Weight"), { target: { value: "0" } });

    fireEvent.click(saveButton());
    expect(screen.getByText("Add at least one set with reps or weight.")).toBeTruthy();
    expect(calls).toHaveLength(0);
  });

  it("refuses a negative set value", () => {
    stubFetch(200, {});
    renderLogger();
    fillSession();
    fireEvent.change(screen.getByLabelText("Weight"), { target: { value: "-50" } });
    fireEvent.click(saveButton());
    expect(screen.getByText("Sets cannot contain negative values.")).toBeTruthy();
    expect(calls).toHaveLength(0);
  });
});

describe("SessionLogger save", () => {
  it("posts the whole session as one composite request", async () => {
    stubFetch(200, { id: "s1", type: "workout_session", child_count: 1 });
    const onSaved = vi.fn();
    const onClose = vi.fn();
    renderLogger({ initialTitle: "Push Day", onSaved, onClose });
    fillSession();

    fireEvent.click(saveButton());

    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0].method).toBe("POST");
    expect(calls[0].url).toContain("/workout-sessions/complete");
    expect(calls[0].body.session.title).toBe("Push Day");
    expect(calls[0].body.session.completed).toBe(true);
    expect(calls[0].body.exercises).toHaveLength(1);
    expect(calls[0].body.exercises[0].exercise_id).toBe("e1");
    expect(calls[0].body.exercises[0].sets[0].reps).toBe(8);
    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    expect(onClose).toHaveBeenCalled();
  });

  it("numbers sets sequentially", async () => {
    stubFetch(200, { id: "s1" });
    renderLogger({ initialTitle: "Push Day" });
    fireEvent.click(screen.getByRole("button", { name: /add exercise/i }));
    fireEvent.click(screen.getByText("Bench Press"));
    fireEvent.click(screen.getByRole("button", { name: /add set/i }));
    fireEvent.change(screen.getAllByLabelText("Reps")[0], { target: { value: "8" } });
    fireEvent.change(screen.getAllByLabelText("Reps")[1], { target: { value: "6" } });
    fireEvent.click(saveButton());

    await waitFor(() => expect(calls).toHaveLength(1));
    const sets = calls[0].body.exercises[0].sets;
    expect(sets.map((s: any) => s.set_number)).toEqual([1, 2]);
    expect(sets.map((s: any) => s.reps)).toEqual([8, 6]);
  });

  it("reports a server rejection and re-enables the button", async () => {
    stubFetch(400, { status: 400, code: "invalid_request", message: "set_number must be between 1 and 100" });
    renderLogger({ initialTitle: "Push Day" });
    fillSession();

    fireEvent.click(saveButton());

    await screen.findByText("That session could not be saved. Please try again.");
    await waitFor(() => expect(saveButton().disabled).toBe(false));
  });

  it("re-enables the button when the network fails", async () => {
    vi.spyOn(globalThis, "fetch").mockRejectedValue(new TypeError("Failed to fetch"));
    renderLogger({ initialTitle: "Push Day" });
    fillSession();

    fireEvent.click(saveButton());

    await screen.findByText("That session could not be saved. Please try again.");
    await waitFor(() => expect(saveButton().disabled).toBe(false));
  });

  it("queues the session and reports it when offline", async () => {
    Object.defineProperty(window.navigator, "onLine", { value: false, configurable: true });
    const onQueued = vi.fn();
    renderLogger({ initialTitle: "Push Day", onQueued });
    fillSession();

    fireEvent.click(saveButton());

    await waitFor(() => expect(onQueued).toHaveBeenCalled());
    expect(calls).toHaveLength(0);
    const queued = await listAll();
    expect(queued).toHaveLength(1);
    expect(queued[0].resource).toBe("/workout-sessions/complete");
  });
});
