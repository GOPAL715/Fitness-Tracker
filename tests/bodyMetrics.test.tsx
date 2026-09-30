// @vitest-environment jsdom
/**
 * The body-measurement save on the Progress screen.
 *
 * <p>The save used to be an upsert that the adapter could not address, so it always returned 404 and
 * no measurement could ever be recorded. It is now a POST that the server resolves per day.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import ProgressView from "../src/views/ProgressView";
import { setAuthTokens } from "../src/lib/api/apiClient";

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

function renderProgress(onRefresh: ReturnType<typeof vi.fn> = vi.fn()) {
  const view = render(
    <ProgressView
      metrics={[]}
      workouts={[]}
      records={[]}
      body={[]}
      sessions={[]}
      goals={[]}
      onRefresh={onRefresh}
    />
  );
  return { onRefresh, view };
}

const saveButton = () => screen.getByRole("button", { name: /save measurement/i }) as HTMLButtonElement;

beforeEach(() => { calls = []; setAuthTokens("test-access-token"); });
afterEach(() => { cleanup(); vi.restoreAllMocks(); setAuthTokens(null, null); });

describe("body measurement save", () => {
  it("POSTs a measurement rather than an unaddressable PUT", async () => {
    stubFetch(200, { id: "b1" });
    const { onRefresh } = renderProgress();

    fireEvent.click(screen.getAllByRole("button", { name: /add measurement|log measurement/i })[0]);
    fireEvent.click(saveButton());

    await waitFor(() => expect(calls.some((c) => c.url.includes("/body-metrics"))).toBe(true));
    /*
     * The save is addressed by which call it is, not by its position. Phase 21 added a trends fetch
     * that fires on mount, so the analytics GET is now the first request the view makes; asserting on
     * `calls[0]` would be asserting that nothing else loads first rather than that the save is a
     * POST, which is what this test is actually about.
     */
    const save = calls.find((c) => c.url.includes("/body-metrics"))!;
    // A POST to the collection: the client has no row id, and the server upserts by day.
    expect(save.method).toBe("POST");
    expect(save.url).toContain("/body-metrics");
    expect(save.url).not.toMatch(/body-metrics\/.+/);
    expect(save.body.weight_lb).toBe(180);
    expect(save.body.metric_date).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    await waitFor(() => expect(onRefresh).toHaveBeenCalled());
  });

  it("surfaces a rejected save and re-enables the button", async () => {
    stubFetch(400, { status: 400, code: "invalid_request", message: "weight_lb must be greater than zero" });
    renderProgress();

    fireEvent.click(screen.getAllByRole("button", { name: /add measurement|log measurement/i })[0]);
    fireEvent.click(saveButton());

    await screen.findByText("Those measurements could not be saved. Please try again.");
    await waitFor(() => expect(saveButton().disabled).toBe(false));
  });

  it("re-enables the button when the request throws", async () => {
    vi.spyOn(globalThis, "fetch").mockRejectedValue(new TypeError("Failed to fetch"));
    renderProgress();

    fireEvent.click(screen.getAllByRole("button", { name: /add measurement|log measurement/i })[0]);
    fireEvent.click(saveButton());

    await screen.findByText("Those measurements could not be saved. Please try again.");
    await waitFor(() => expect(saveButton().disabled).toBe(false));
  });

  it("does not refresh after a failed save", async () => {
    stubFetch(500);
    const { onRefresh } = renderProgress();

    fireEvent.click(screen.getAllByRole("button", { name: /add measurement|log measurement/i })[0]);
    fireEvent.click(saveButton());

    await screen.findByText("Those measurements could not be saved. Please try again.");
    expect(onRefresh).not.toHaveBeenCalled();
  });
});
