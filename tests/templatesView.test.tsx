// @vitest-environment jsdom
/**
 * Template creation and the atomic composite update.
 *
 * <p>The edit path used to be three separate requests - update the parent, delete the children,
 * re-insert them - so a failure between them left the template with no exercises. These pin the
 * single-request behaviour that replaced it.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import TemplatesView from "../src/views/TemplatesView";
import { setAuthTokens } from "../src/lib/api/apiClient";
import type { Exercise, TemplateExercise, WorkoutTemplate } from "../src/lib/types";

const exercise: Exercise = {
  id: "e1", name: "Bench Press", description: "", muscle_group: "Chest",
  secondary_muscles: [], equipment: "Barbell", difficulty: "Intermediate", instructions: "", is_compound: true,
};

const templateItem: TemplateExercise & { exercise: Exercise | null } = {
  id: "ti1", template_id: "t1", exercise_id: "e1", order_index: 0,
  target_sets: 3, target_reps: "8-12", target_weight: null, notes: null, exercise,
};

const template: WorkoutTemplate & { items: (TemplateExercise & { exercise: Exercise | null })[] } = {
  id: "t1", name: "Push Day", description: "Chest", workout_type: "Strength",
  estimated_minutes: 45, is_favorite: false, items: [templateItem],
};

type Call = { url: string; method: string; body: any };
let calls: Call[] = [];

function stubFetch(status: number) {
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    calls.push({
      url: String(input),
      method: init?.method ?? "GET",
      body: init?.body ? JSON.parse(String(init.body)) : undefined,
    });
    return {
      ok: status >= 200 && status < 300, status,
      json: async () => ({ id: "t1", type: "workout_template", child_count: 1 }),
    } as Response;
  });
}

function renderTemplates(templates = [template], onRefresh = vi.fn()) {
  render(<TemplatesView templates={templates} exercises={[exercise]} onRefresh={onRefresh} onStartFromTemplate={vi.fn()} />);
  return { onRefresh };
}

beforeEach(() => { calls = []; setAuthTokens("test-access-token"); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); setAuthTokens(null, null); });

describe("TemplatesView creation", () => {
  it("creates a template through the composite endpoint", async () => {
    stubFetch(200);
    const { onRefresh } = renderTemplates([]);

    fireEvent.click(screen.getByRole("button", { name: /new template/i }));
    fireEvent.change(screen.getByPlaceholderText(/push day/i), { target: { value: "Pull Day" } });
    fireEvent.click(screen.getByRole("button", { name: /add exercise/i }));
    fireEvent.click(screen.getByText("Bench Press"));
    fireEvent.click(screen.getByRole("button", { name: /save template/i }));

    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0].method).toBe("POST");
    expect(calls[0].url).toContain("/workout-templates/complete");
    expect(calls[0].body.template.name).toBe("Pull Day");
    expect(calls[0].body.exercises).toHaveLength(1);
    await waitFor(() => expect(onRefresh).toHaveBeenCalled());
  });

  it("refuses to save a template with no name", () => {
    stubFetch(200);
    renderTemplates([]);
    fireEvent.click(screen.getByRole("button", { name: /new template/i }));
    fireEvent.click(screen.getByRole("button", { name: /save template/i }));
    expect(screen.getByText("Give the template a name.")).toBeTruthy();
    expect(calls).toHaveLength(0);
  });

  it("refuses to save a template with no exercises", () => {
    stubFetch(200);
    renderTemplates([]);
    fireEvent.click(screen.getByRole("button", { name: /new template/i }));
    fireEvent.change(screen.getByPlaceholderText(/push day/i), { target: { value: "Empty" } });
    fireEvent.click(screen.getByRole("button", { name: /save template/i }));
    expect(screen.getByText("Add at least one exercise to the template.")).toBeTruthy();
    expect(calls).toHaveLength(0);
  });
});

describe("TemplatesView atomic edit", () => {
  it("sends one composite update instead of a delete-and-reinsert sequence", async () => {
    stubFetch(200);
    const { onRefresh } = renderTemplates();

    fireEvent.click(screen.getByRole("button", { name: /^edit$/i }));
    fireEvent.change(screen.getByPlaceholderText(/push day/i), { target: { value: "Push Day v2" } });
    fireEvent.click(screen.getByRole("button", { name: /save template/i }));

    await waitFor(() => expect(calls).toHaveLength(1));
    // Exactly one request: the old three-step path could strand the template with no exercises.
    expect(calls[0].method).toBe("PUT");
    expect(calls[0].url).toContain("/workout-templates/t1/complete");
    expect(calls[0].body.template.name).toBe("Push Day v2");
    expect(calls[0].body.exercises[0].exercise_id).toBe("e1");
    // The id comes from the path, never from the body.
    expect(calls[0].body.template).not.toHaveProperty("id");
    await waitFor(() => expect(onRefresh).toHaveBeenCalled());
  });

  it("never issues a delete for the template's exercises while editing", async () => {
    stubFetch(200);
    renderTemplates();

    fireEvent.click(screen.getByRole("button", { name: /^edit$/i }));
    fireEvent.click(screen.getByRole("button", { name: /save template/i }));

    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls.some((c) => c.method === "DELETE")).toBe(false);
  });

  it("reports a rejected update and refreshes nothing", async () => {
    stubFetch(404);
    const { onRefresh } = renderTemplates();

    fireEvent.click(screen.getByRole("button", { name: /^edit$/i }));
    fireEvent.click(screen.getByRole("button", { name: /save template/i }));

    await screen.findByText("That template could not be saved.");
    // The server rolled back, so the view must not pretend the change landed.
    expect(onRefresh).not.toHaveBeenCalled();
  });
});
