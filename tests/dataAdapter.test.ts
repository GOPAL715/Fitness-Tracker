/**
 * The chainable data adapter that every view uses to reach the Spring Boot API.
 *
 * <p>These lock in the two behaviours the rest of the app depends on: a mutation stays chainable so a
 * trailing .eq() can still contribute the resource id, and a failure comes back as `error` rather
 * than as a thrown exception.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiData } from "../src/lib/api/dataAdapter";

type Call = { url: string; method: string; body: unknown };

let calls: Call[] = [];

/** Stubs fetch and records every request the adapter makes. */
function stubFetch(responder: (url: string, method: string) => { status: number; body: unknown }) {
  const spy = vi.spyOn(globalThis, "fetch");
  spy.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = init?.method ?? "GET";
    calls.push({ url, method, body: init?.body ? JSON.parse(String(init.body)) : undefined });
    const { status, body } = responder(url, method);
    return {
      ok: status >= 200 && status < 300,
      status,
      json: async () => body,
    } as Response;
  });
  return spy;
}

beforeEach(() => { calls = []; });
afterEach(() => { vi.restoreAllMocks(); });

describe("dataAdapter request building", () => {
  it("sends update(...).eq(...) as a PUT to the row endpoint", async () => {
    const spy = stubFetch(() => ({ status: 200, body: { id: "p1", display_name: "Sam" } }));

    const { data, error } = await apiData
      .from("fitness_profile")
      .update({ display_name: "Sam" })
      .eq("id", "p1");

    expect(error).toBeNull();
    expect(data).toEqual({ id: "p1", display_name: "Sam" });
    expect(calls).toHaveLength(1);
    // The trailing .eq() is what turns the collection URL into the row URL.
    expect(calls[0].url).toContain("/fitness-profile/p1");
    expect(calls[0].method).toBe("PUT");
    expect(calls[0].body).toEqual({ display_name: "Sam" });
    spy.mockRestore();
  });

  it("applies the filter that follows update() rather than firing before it", async () => {
    const spy = stubFetch(() => ({ status: 200, body: {} }));

    // No await between update() and eq(): the request must not be issued until the chain resolves.
    const chain = apiData.from("fitness_profile").update({ goal: "Lose fat" });
    expect(calls).toHaveLength(0);
    chain.eq("id", "p42");

    await chain;
    expect(calls[0].url).toContain("/fitness-profile/p42");
    spy.mockRestore();
  });

  it("uses the first filter as the row id when several are chained", async () => {
    const spy = stubFetch(() => ({ status: 200, body: {} }));

    await apiData.from("food_scan_items").update({ confirmed_grams: 100 }).eq("scan_id", "s1").eq("food_name", "Oats");

    expect(calls[0].url).toContain("/food-scan-items/s1");
    spy.mockRestore();
  });

  it("maps a table name to the hyphenated API resource", async () => {
    const spy = stubFetch(() => ({ status: 200, body: {} }));

    await apiData.from("coach_notifications").update({ is_read: true }).eq("id", "n1");

    expect(calls[0].url).toContain("/coach-notifications/n1");
    spy.mockRestore();
  });

  it("sends insert to the collection endpoint and delete to the row endpoint", async () => {
    const spy = stubFetch(() => ({ status: 200, body: { id: "new" } }));

    await apiData.from("habits").insert({ name: "Hydrate" });
    await apiData.from("habits").delete().eq("id", "h1");

    expect(calls[0].url).toContain("/habits");
    expect(calls[0].method).toBe("POST");
    expect(calls[1].url).toContain("/habits/h1");
    expect(calls[1].method).toBe("DELETE");
    spy.mockRestore();
  });

  it("keeps insert().select().maybeSingle() working", async () => {
    const spy = stubFetch(() => ({ status: 200, body: { id: "m1" } }));

    const { data } = await apiData.from("meals").insert({ name: "Lunch" }).select("id").maybeSingle();

    expect(data).toEqual({ id: "m1" });
    expect(calls[0].method).toBe("POST");
    spy.mockRestore();
  });

  it("unwraps the first element for maybeSingle over a list response", async () => {
    const spy = stubFetch(() => ({ status: 200, body: [{ id: "a" }, { id: "b" }] }));

    const { data } = await apiData.from("exercises").select().eq("name", "Squat").maybeSingle();

    expect(data).toEqual({ id: "a" });
    spy.mockRestore();
  });
});

describe("dataAdapter error handling", () => {
  it("returns the saved profile on success", async () => {
    const spy = stubFetch(() => ({ status: 200, body: { id: "p1", display_name: "Sam" } }));

    const { data, error } = await apiData.from("fitness_profile").update({ display_name: "Sam" }).eq("id", "p1");

    expect(error).toBeNull();
    expect(data).toEqual({ id: "p1", display_name: "Sam" });
    spy.mockRestore();
  });

  it("returns { data: null, error } when the server rejects the update", async () => {
    const spy = stubFetch(() => ({
      status: 400,
      body: { status: 400, code: "invalid_request", message: "Unsupported field: sleep_target_hours" },
    }));

    const { data, error } = await apiData.from("fitness_profile").update({ sleep_target_hours: 8 }).eq("id", "p1");

    // Failure is reported, never thrown, so a caller that destructures { data, error } is correct.
    expect(data).toBeNull();
    expect(error).not.toBeNull();
    expect(error?.status).toBe(400);
    expect(error?.message).toBe("Unsupported field: sleep_target_hours");
    spy.mockRestore();
  });

  it("reports a network failure as an error rather than rejecting", async () => {
    const spy = vi.spyOn(globalThis, "fetch").mockRejectedValue(new TypeError("Failed to fetch"));

    const { data, error } = await apiData.from("fitness_profile").update({ goal: "Build muscle" }).eq("id", "p1");

    expect(data).toBeNull();
    expect(error).not.toBeNull();
    expect(error?.status).toBe(0);
    spy.mockRestore();
  });
});

