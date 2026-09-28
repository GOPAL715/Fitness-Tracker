// @vitest-environment jsdom
/**
 * NutritionView, through the real component and the real adapter.
 *
 * <p>Pins the Phase 8 nutrition contract at the level the user sees it. Meal macros are derived
 * server-side now, so the screen must display the stored values rather than recomputing its own; a
 * null macro must not become NaN in a ring; fiber must be shown; and a rejected save must not
 * pretend to have worked.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import NutritionView from "../src/views/NutritionView";
import { setAuthTokens } from "../src/lib/api/apiClient";
import { todayISO } from "../src/lib/utils";
import type { Food, Meal, MealItem, Profile } from "../src/lib/types";

type Call = { url: string; method: string; body: any };
let calls: Call[] = [];

/** Answers every request, recording it, so assertions can be made about what was sent. */
function stubFetch(responder?: (url: string, method: string) => { status: number; body: unknown }) {
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = init?.method ?? "GET";
    calls.push({ url, method, body: init?.body ? JSON.parse(String(init.body)) : undefined });
    const r = responder?.(url, method) ?? { status: 200, body: {} };
    return { ok: r.status >= 200 && r.status < 300, status: r.status, json: async () => r.body } as Response;
  });
}

const food: Food = {
  id: "f1", name: "Cooked white rice", category: "Grains", serving_size: 250, serving_unit: "g",
  calories: 130, protein_g: 2.7, carbs_g: 28, fat_g: 0.3, fiber_g: 0.4, sugar_g: 0.1,
  sodium_mg: 1, source: "test",
};

const meal = (over: Partial<Meal> = {}): Meal => ({
  id: "m1", user_id: "u1", meal_date: todayISO(), meal_type: "Lunch", name: "Rice bowl",
  source: "manual", calories: 260, protein_g: 5.4, carbs_g: 56, fat_g: 0.6, fiber_g: 0.8,
  created_at: "2026-01-01T00:00:00Z", ...over,
} as Meal);

const item = (over: Partial<MealItem> = {}): MealItem => ({
  id: "i1", meal_id: "m1", food_id: "f1", food_name: "Cooked white rice",
  quantity: 1, grams: 200, calories: 260, protein_g: 5.4, carbs_g: 56, fat_g: 0.6, fiber_g: 0.8,
  source: "manual", ...over,
} as MealItem);

const profile = { calorie_target: 2000, protein_target_g: 150, water_target_oz: 100 } as Profile;

function renderNutrition(props: Partial<Parameters<typeof NutritionView>[0]> = {}) {
  const onRefresh = vi.fn();
  render(
    <NutritionView meals={[]} mealItems={[]} foods={[food]} profile={profile}
      todayMetric={null} onRefresh={onRefresh} {...props} />
  );
  return onRefresh;
}

beforeEach(() => { calls = []; setAuthTokens("test-access-token", "test-refresh-token"); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); setAuthTokens(null, null); });

describe("empty nutrition state", () => {
  it("explains an empty day instead of showing a blank screen", () => {
    renderNutrition();
    expect(screen.getByText("No meals logged today")).toBeTruthy();
  });

  it("offers both ways of logging a meal", () => {
    renderNutrition();
    expect(screen.getAllByRole("button", { name: /manual entry/i }).length).toBeGreaterThan(0);
    expect(screen.getAllByRole("button", { name: /scan food/i }).length).toBeGreaterThan(0);
  });
});

describe("meal totals are displayed as stored", () => {
  it("shows the macros the server derived rather than recomputing them", () => {
    renderNutrition({ meals: [meal()], mealItems: [item()] });
    expect(screen.getByText("260 kcal")).toBeTruthy();
    expect(screen.getByText("5.4g protein")).toBeTruthy();
    expect(screen.getByText("56g carbs")).toBeTruthy();
    expect(screen.getByText("0.6g fat")).toBeTruthy();
  });

  it("shows fiber for the day", () => {
    renderNutrition({ meals: [meal()], mealItems: [item()] });
    expect(screen.getByText("fiber today")).toBeTruthy();
    expect(screen.getByText("0.8g")).toBeTruthy();
  });
});

describe("null nutrition is safe", () => {
  it("treats a meal with null macros as zero rather than rendering NaN", () => {
    renderNutrition({
      meals: [meal({ calories: null as any, protein_g: null as any, carbs_g: null as any,
        fat_g: null as any, fiber_g: null as any })],
      mealItems: [],
    });
    expect(screen.queryByText(/NaN/)).toBeNull();
    expect(screen.getByText("Rice bowl")).toBeTruthy();
  });

  it("does not break the daily rings when a macro is missing", () => {
    renderNutrition({ meals: [meal({ protein_g: null as any })], mealItems: [item()] });
    expect(screen.queryByText(/NaN/)).toBeNull();
    expect(screen.queryByText(/Infinity/)).toBeNull();
  });
});

describe("food search", () => {
  it("filters the picker and reports an empty result", async () => {
    renderNutrition();
    fireEvent.click(screen.getAllByRole("button", { name: /manual entry/i })[0]);
    fireEvent.click(screen.getByRole("button", { name: /add food/i }));

    const box = await screen.findByPlaceholderText(/search the nutrition database/i);
    fireEvent.change(box, { target: { value: "zzz-nothing" } });
    await waitFor(() => expect(screen.getByText(/no matching food found/i)).toBeTruthy());

    fireEvent.change(box, { target: { value: "rice" } });
    await waitFor(() => expect(screen.getByText("Cooked white rice")).toBeTruthy());
  });
});

describe("manual meal entry", () => {
  it("previews the canonical per-100g total for the chosen portion", async () => {
    stubFetch();
    renderNutrition();
    fireEvent.click(screen.getAllByRole("button", { name: /manual entry/i })[0]);
    fireEvent.click(screen.getByRole("button", { name: /add food/i }));
    fireEvent.click(await screen.findByText("Cooked white rice"));

    // The draft starts at 100 g of a food carrying 130 kcal per 100 g, so the preview is 130. The
    // food's serving_size is 250 g and must not scale it: that was the whole of the old divergence.
    await waitFor(() => expect(screen.getByText("130")).toBeTruthy());
    expect(screen.queryByText("32.5")).toBeNull();
  });

  it("sends foods and portion weights and never sends totals of its own", async () => {
    stubFetch();
    renderNutrition();
    fireEvent.click(screen.getAllByRole("button", { name: /manual entry/i })[0]);
    fireEvent.click(screen.getByRole("button", { name: /add food/i }));
    fireEvent.click(await screen.findByText("Cooked white rice"));
    fireEvent.click(screen.getByRole("button", { name: /save meal/i }));

    await waitFor(() => expect(calls.some((c) => c.url.includes("/meals/complete"))).toBe(true));
    const post = calls.find((c) => c.url.includes("/meals/complete"))!;
    expect(post.method).toBe("POST");
    // The server derives the totals, so the client must not assert them.
    expect(JSON.stringify(post.body)).not.toMatch(/"calories"/);
    expect(post.body.items[0]).toMatchObject({ food_id: "f1", grams: 100 });
  });

  it("refuses to save a meal with no items", async () => {
    stubFetch();
    renderNutrition();
    fireEvent.click(screen.getAllByRole("button", { name: /manual entry/i })[0]);
    fireEvent.click(screen.getByRole("button", { name: /save meal/i }));
    expect(await screen.findByText(/add at least one food item/i)).toBeTruthy();
    expect(calls.some((c) => c.method === "POST")).toBe(false);
  });
});

describe("error handling", () => {
  it("surfaces a failed save and keeps the dialog open", async () => {
    stubFetch(() => ({ status: 500, body: {} }));
    renderNutrition();
    fireEvent.click(screen.getAllByRole("button", { name: /manual entry/i })[0]);
    fireEvent.click(screen.getByRole("button", { name: /add food/i }));
    fireEvent.click(await screen.findByText("Cooked white rice"));
    fireEvent.click(screen.getByRole("button", { name: /save meal/i }));

    expect(await screen.findByText(/could not be saved/i)).toBeTruthy();
    expect(screen.getByRole("button", { name: /save meal/i })).toBeTruthy();
  });
});

describe("deleting a meal", () => {
  it("issues a delete and refreshes when it succeeded", async () => {
    stubFetch();
    const onRefresh = renderNutrition({ meals: [meal()], mealItems: [item()] });
    fireEvent.click(screen.getByLabelText(/delete/i));
    await waitFor(() => expect(calls.some((c) => c.method === "DELETE" && c.url.includes("/meals/m1"))).toBe(true));
    await waitFor(() => expect(onRefresh).toHaveBeenCalled());
  });

  it("does not refresh when the delete was rejected", async () => {
    stubFetch((_u, m) => (m === "DELETE" ? { status: 403, body: {} } : { status: 200, body: {} }));
    const onRefresh = renderNutrition({ meals: [meal()], mealItems: [item()] });
    fireEvent.click(screen.getByLabelText(/delete/i));
    await waitFor(() => expect(calls.some((c) => c.method === "DELETE")).toBe(true));
    expect(onRefresh).not.toHaveBeenCalled();
  });
});

