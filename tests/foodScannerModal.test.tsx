// @vitest-environment jsdom
/**
 * The AI food scanner modal, through the real component and the real adapter.
 *
 * <p>Pins the Phase 8 scanner flow. It used to build the meal itself: insert a meal, insert its
 * items, then patch the scan, all from the browser, so a failure part way through left a meal with
 * no items and a scan pointing at it, and the totals it displayed came from a different calculation
 * than the one the server stored. Confirmation is now one transactional server call, corrections are
 * addressed by item id rather than by food name, and fiber is preserved end to end.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import FoodScannerModal from "../src/views/FoodScannerModal";
import { setAuthTokens } from "../src/lib/api/apiClient";
import { todayISO } from "../src/lib/utils";
import type { Food } from "../src/lib/types";

type Call = { url: string; method: string; body: any };
let calls: Call[] = [];

const food: Food = {
  id: "f1", name: "Cooked white rice", category: "Grains", serving_size: 250, serving_unit: "g",
  calories: 130, protein_g: 2.7, carbs_g: 28, fat_g: 0.3, fiber_g: 0.4, sugar_g: 0.1,
  sodium_mg: 1, source: "test",
};

const scan = (over: Record<string, unknown> = {}) => ({
  id: "scan-1", status: "completed", model: "test-model", meal_id: null, error: null,
  items: [{ id: "item-1", foodId: "f1", name: "Cooked white rice", grams: 200, confidence: 0.9 }],
  totalCalories: 260, totalProteinG: 5.4, totalCarbsG: 56, totalFatG: 0.6,
  ...over,
});

/** Builds a small JPEG so the component's own image validation accepts the upload. */
const jpeg = () => new File([new Uint8Array([0xff, 0xd8, 0xff, 0x00])], "meal.jpg", { type: "image/jpeg" });

function stubFetch(responder?: (url: string, method: string) => { status: number; body: unknown } | Promise<{ status: number; body: unknown }>) {
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = init?.method ?? "GET";
    // A FormData body is the photo upload; it is not JSON and must not be parsed as such.
    const isJson = typeof init?.body === "string";
    calls.push({ url, method, body: isJson ? JSON.parse(init!.body as string) : undefined });
    // Awaited so a responder can hold a response open to test an in-flight state.
    const r = (await responder?.(url, method)) ?? { status: 200, body: {} };
    return { ok: r.status >= 200 && r.status < 300, status: r.status, json: async () => r.body } as Response;
  });
}

function renderModal(props: Partial<Parameters<typeof FoodScannerModal>[0]> = {}) {
  const onSaved = vi.fn();
  const onClose = vi.fn();
  render(<FoodScannerModal foods={[food]} onClose={onClose} onSaved={onSaved} {...props} />);
  return { onSaved, onClose };
}

const callFor = (frag: string) => calls.find((c) => c.url.includes(frag));

/** Walks the modal from the file chooser to the review stage with a stubbed scan result. */
async function reachReview(responder?: Parameters<typeof stubFetch>[0]) {
  stubFetch(responder);
  renderModal();
  fireEvent.change(screen.getByTestId("scanner-file"), { target: { files: [jpeg()] } });
  fireEvent.click(await screen.findByRole("button", { name: /analyze food/i }));
  await screen.findByRole("button", { name: /confirm meal/i });
}

beforeEach(() => { calls = []; setAuthTokens("test-access-token", "test-refresh-token"); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); setAuthTokens(null, null); });

describe("initial upload", () => {
  it("offers both a camera and a file chooser", () => {
    renderModal();
    expect(screen.getByRole("button", { name: /take a photo/i })).toBeTruthy();
    expect(screen.getByRole("button", { name: /upload a photo/i })).toBeTruthy();
  });

  it("rejects a file that is not an image before anything is uploaded", async () => {
    stubFetch();
    renderModal();
    const bad = new File(["nope"], "meal.txt", { type: "text/plain" });
    fireEvent.change(screen.getByTestId("scanner-file"), { target: { files: [bad] } });
    expect(await screen.findByRole("alert")).toBeTruthy();
    expect(calls).toHaveLength(0);
  });
});

describe("scan result", () => {
  it("uploads the photo and shows the detected item", async () => {
    await reachReview(() => ({ status: 201, body: scan() }));
    expect(callFor("/api/v1/food-scans")).toBeTruthy();
    expect(screen.getByText("Cooked white rice")).toBeTruthy();
  });

  it("estimates calories on the per-100g basis, not the serving basis", async () => {
    await reachReview(() => ({ status: 201, body: scan() }));
    // 200 g of a 130 kcal-per-100 g food is 260 kcal, computed locally from the catalog row.
    await waitFor(() => expect(screen.getByText(/estimated calories/i)).toBeTruthy());
    expect(screen.queryByText(/104 kcal/)).toBeNull();
  });

  it("reports a failed scan instead of showing an empty review", async () => {
    stubFetch(() => ({ status: 500, body: {} }));
    renderModal();
    fireEvent.change(screen.getByTestId("scanner-file"), { target: { files: [jpeg()] } });
    fireEvent.click(await screen.findByRole("button", { name: /analyze food/i }));
    expect(await screen.findByRole("alert")).toBeTruthy();
    expect(screen.queryByRole("button", { name: /confirm meal/i })).toBeNull();
  });

  it("reports a scan that detected nothing", async () => {
    stubFetch(() => ({ status: 201, body: scan({ items: [] }) }));
    renderModal();
    fireEvent.change(screen.getByTestId("scanner-file"), { target: { files: [jpeg()] } });
    fireEvent.click(await screen.findByRole("button", { name: /analyze food/i }));
    // No item was detected, so there is nothing to confirm and the reason is shown instead.
    expect(await screen.findByRole("alert")).toBeTruthy();
    expect(screen.queryByRole("button", { name: /confirm meal/i })).toBeNull();
  });
});

describe("correction by item id", () => {
  it("sends the server's item id, not the food name, when grams change", async () => {
    await reachReview(() => ({ status: 201, body: scan() }));
    const grams = screen.getByLabelText(/grams/i);
    fireEvent.change(grams, { target: { value: "300" } });

    fireEvent.click(screen.getByRole("button", { name: /confirm meal/i }));
    await waitFor(() => expect(callFor("/items")).toBeTruthy());
    const put = callFor("/items")!;
    expect(put.method).toBe("PUT");
    expect(put.body[0].itemId).toBe("item-1");
    expect(put.body[0].grams).toBe(300);
  });

  it("sends no correction at all when the user changed nothing", async () => {
    await reachReview(() => ({ status: 201, body: scan() }));
    fireEvent.click(screen.getByRole("button", { name: /confirm meal/i }));
    await waitFor(() => expect(callFor("/confirm")).toBeTruthy());
    expect(callFor("/items")).toBeUndefined();
  });
});

describe("confirmation", () => {
  it("creates the meal through one server call and never from the browser", async () => {
    await reachReview(() => ({ status: 201, body: scan() }));
    fireEvent.click(screen.getByRole("button", { name: /confirm meal/i }));

    await waitFor(() => expect(callFor("/confirm")).toBeTruthy());
    const post = callFor("/confirm")!;
    expect(post.method).toBe("POST");
    expect(post.body.mealDate).toBe(todayISO());

    // The browser must not assemble the meal itself any more: no meal insert, no item insert.
    expect(callFor("/api/v1/meals?")).toBeUndefined();
    expect(calls.filter((c) => c.url.includes("/meal-items"))).toHaveLength(0);
    // And it must not dictate the totals either.
    expect(JSON.stringify(post.body)).not.toMatch(/"calories"/);
  });

  it("refuses to confirm while an item has no catalog match", async () => {
    await reachReview(() => ({
      status: 201,
      body: scan({ items: [{ id: "item-1", foodId: null, name: "Mystery", grams: 200, confidence: 0.4 }] }),
    }));
    fireEvent.click(screen.getByRole("button", { name: /confirm meal/i }));
    expect(await screen.findByRole("alert")).toBeTruthy();
    expect(callFor("/confirm")).toBeUndefined();
  });

  it("surfaces a rejected confirmation and stays open", async () => {
    await reachReview((url, method) => {
      if (url.includes("/confirm")) return { status: 409, body: { message: "Scan was already confirmed" } };
      return method === "POST" ? { status: 201, body: scan() } : { status: 200, body: {} };
    });
    fireEvent.click(screen.getByRole("button", { name: /confirm meal/i }));
    expect(await screen.findByRole("alert")).toBeTruthy();
    expect(screen.getByRole("button", { name: /confirm meal/i })).toBeTruthy();
  });
});

describe("busy state", () => {
  it("disables confirmation while the request is in flight and does not double-submit", async () => {
    // The confirmation response is held open, so the second click lands while the first is pending.
    let release: () => void = () => {};
    const held = new Promise<void>((r) => { release = r; });
    stubFetch(async (url) => {
      if (url.includes("/confirm")) { await held; return { status: 200, body: scan({ status: "confirmed" }) }; }
      return { status: 201, body: scan() };
    });
    renderModal();
    fireEvent.change(screen.getByTestId("scanner-file"), { target: { files: [jpeg()] } });
    fireEvent.click(await screen.findByRole("button", { name: /analyze food/i }));
    const confirm = await screen.findByRole("button", { name: /confirm meal/i });

    fireEvent.click(confirm);
    await waitFor(() => expect((confirm as HTMLButtonElement).disabled).toBe(true));
    fireEvent.click(confirm);
    // A second click while saving must not produce a second confirmation.
    expect(calls.filter((c) => c.url.includes("/confirm"))).toHaveLength(1);
    expect(calls.filter((c) => c.url.includes("/confirm"))[0].method).toBe("POST");

    release();
    await waitFor(() => expect(calls.filter((c) => c.url.includes("/confirm"))).toHaveLength(1));
  });
});

